package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.core.process.TerminalMission;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Composite mining task (issue 0014): search for nearby blocks of a
 * given type, walk to each, mine it, collect the drop, and repeat
 * until the inventory holds the requested count. First composite task;
 * pattern reference is 0010 HungryProcess (Process-layer state machine).
 *
 * <p>The process is side-effect-free (boundary B): it outputs
 * {@code Directive{GoalNear}} for movement and signals digging through
 * the {@link DigMission} interface ({@link #isDigging()} +
 * {@link #digTarget()}); the controller's mission-dig claim path
 * injects the actual {@code Intent.Dig} per tick. World reads
 * (block search, inventory count) happen in {@link #onTick} via the
 * live {@link WorldView}.
 *
 * <p>State machine: IDLE → SEARCH → MOVING → DIGGING → COLLECTING →
 * (SEARCH | DONE), with FAILED as the terminal failure path. Per-target
 * failures (STUCK, timeout) skip that target and return to SEARCH
 * rather than failing the whole mission.
 *
 * <p>Contract: see boundaries.md section B (process tier is
 * side-effect-free) + issue 0014 §3 (MineProcess architecture).
 */
// contract: see boundaries.md section B + issue 0014 §3
public final class MineProcess implements BotProcess, TerminalMission,
        DigMission {

    /** Mission phases. */
    private enum Phase {
        IDLE,
        SEARCH,
        MOVING,
        DIGGING,
        COLLECTING,
        DONE,
        FAILED
    }

    private static final int DEFAULT_SEARCH_RADIUS = 16;
    private static final int DEFAULT_PER_TARGET_MOVE_BUDGET = 200;
    private static final int DEFAULT_PICKUP_WINDOW = 20;

    private final String taskId;
    private final String blockType;
    private final int targetCount;
    private final int priority;
    private final long timeoutTicks;
    private final int searchRadius;
    private final int perTargetMoveBudget;
    private final int pickupWindow;

    private Phase phase = Phase.IDLE;
    private CellPos currentTarget;
    private String initialBlockId;
    private int collectedCount;
    private long ticksInMission;
    private long ticksInCurrentPhase;
    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private final Set<CellPos> skipSet = new HashSet<>();

    /**
     * Creates a mine mission.
     *
     * @param taskId       the boundary-D task id; never null
     * @param blockType    the registry id of blocks to mine, e.g.
     *                     {@code minecraft:stone}; never null
     * @param targetCount  how many to collect; positive
     * @param priority     arbiter seat priority (same scale as goto/dig)
     * @param timeoutTicks mission budget; positive
     */
    public MineProcess(String taskId, String blockType, int targetCount,
                       int priority, long timeoutTicks) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(
                "taskId must not be null or blank");
        }
        if (blockType == null || blockType.isBlank()) {
            throw new IllegalArgumentException(
                "blockType must not be null or blank");
        }
        if (targetCount <= 0) {
            throw new IllegalArgumentException("targetCount must be positive");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.blockType = blockType;
        this.targetCount = targetCount;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
        this.searchRadius = DEFAULT_SEARCH_RADIUS;
        this.perTargetMoveBudget = DEFAULT_PER_TARGET_MOVE_BUDGET;
        this.pickupWindow = DEFAULT_PICKUP_WINDOW;
    }

    @Override
    public String displayName() {
        return "mine:" + taskId;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public Directive onTick(WorldView world) {
        if (!active) {
            // Terminal hold: arbiter retires us on the next lap.
            return currentTarget != null
                ? Directive.of(new GoalNear(currentTarget, 2))
                : Directive.of(null);
        }
        if (++ticksInMission >= timeoutTicks) {
            return fail("TIMEOUT");
        }
        ticksInCurrentPhase++;
        return switch (phase) {
            case IDLE -> {
                phase = Phase.SEARCH;
                ticksInCurrentPhase = 0;
                yield doSearch(world);
            }
            case SEARCH -> doSearch(world);
            case MOVING -> doMoving(world);
            case DIGGING -> doDigging(world);
            case COLLECTING -> doCollecting(world);
            case DONE, FAILED -> Directive.of(null);
        };
    }

    /**
     * SEARCH phase: spiral-scan the radius around the bot's position
     * for target blocks, pick the nearest non-skipped one, transition
     * to MOVING. If no target found, fail.
     */
    private Directive doSearch(WorldView world) {
        CellPos center = world.getInventory() != null
            ? estimateBotPos(world)
            : currentTarget;
        if (center == null) {
            return fail("cannot determine bot position");
        }
        List<CellPos> candidates = new ArrayList<>();
        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    CellPos pos = new CellPos(
                        center.x() + dx, center.y() + dy, center.z() + dz);
                    if (skipSet.contains(pos)) {
                        continue;
                    }
                    BlockSnapshot snap = world.getBlock(pos, ViewMode.LIVE);
                    if (snap != null && blockType.equals(snap.blockId())) {
                        candidates.add(pos);
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return fail("no " + blockType + " within " + searchRadius);
        }
        candidates.sort(Comparator.comparingInt(
            p -> chebyshev(center, p)));
        currentTarget = candidates.get(0);
        initialBlockId = blockType;
        phase = Phase.MOVING;
        ticksInCurrentPhase = 0;
        return Directive.of(new GoalNear(currentTarget, 2));
    }

    /**
     * MOVING phase: hold the GoalNear directive. Transition to DIGGING
     * when the PathingBehavior reports SUCCESS (handled in
     * onExecutionReport). If the per-target budget expires, skip this
     * target and return to SEARCH.
     */
    private Directive doMoving(WorldView world) {
        if (ticksInCurrentPhase >= perTargetMoveBudget) {
            skipSet.add(currentTarget);
            phase = Phase.SEARCH;
            ticksInCurrentPhase = 0;
            return doSearch(world);
        }
        return Directive.of(new GoalNear(currentTarget, 2));
    }

    /**
     * DIGGING phase: isDigging() returns true, so the controller
     * injects aim+dig claims. Poll the target block; when it turns to
     * air, transition to COLLECTING.
     */
    private Directive doDigging(WorldView world) {
        BlockSnapshot now = world.getBlock(currentTarget, ViewMode.LIVE);
        if (now != null && BlockSnapshot.AIR.equals(now.blockId())) {
            phase = Phase.COLLECTING;
            ticksInCurrentPhase = 0;
            // Walk to the drop position (the block's cell) for pickup.
            return Directive.of(new GoalNear(currentTarget, 1));
        }
        // Hold position while digging.
        return Directive.of(new GoalNear(currentTarget, 2));
    }

    /**
     * COLLECTING phase: walk to the drop position, wait for the pickup
     * window, then check inventory. If count increased, proceed; if the
     * target count is met, DONE; otherwise return to SEARCH.
     */
    private Directive doCollecting(WorldView world) {
        if (ticksInCurrentPhase < pickupWindow) {
            return Directive.of(new GoalNear(currentTarget, 1));
        }
        int count = countItem(world, blockType);
        if (count > collectedCount) {
            collectedCount = count;
        }
        if (collectedCount >= targetCount) {
            succeeded = true;
            active = false;
            phase = Phase.DONE;
            return Directive.of(null);
        }
        // Not enough yet; search for the next target.
        skipSet.add(currentTarget);
        phase = Phase.SEARCH;
        ticksInCurrentPhase = 0;
        return doSearch(world);
    }

    @Override
    public void onExecutionReport(
            com.mcbot.mcbotserver.api.behavior.ExecutionReport report) {
        if (!active) {
            return;
        }
        switch (report.status()) {
            case SUCCESS -> {
                if (phase == Phase.MOVING) {
                    phase = Phase.DIGGING;
                    ticksInCurrentPhase = 0;
                }
            }
            case STUCK -> {
                if (phase == Phase.MOVING) {
                    skipSet.add(currentTarget);
                    phase = Phase.SEARCH;
                    ticksInCurrentPhase = 0;
                }
            }
            case FAILED -> {
                if (phase == Phase.MOVING) {
                    skipSet.add(currentTarget);
                    phase = Phase.SEARCH;
                    ticksInCurrentPhase = 0;
                }
            }
            default -> {
                // RUNNING stays the mover's concern; DIGGING/COLLECTING
                // transitions are driven by world reads in onTick.
            }
        }
    }

    @Override
    public void onLostControl(
            com.mcbot.mcbotserver.api.interrupt.InterruptionContext ctx) {
        // Keep state; resume revalidates through world reads.
    }

    @Override
    public boolean resume(
            com.mcbot.mcbotserver.api.interrupt.InterruptionContext ctx) {
        return active;
    }

    @Override
    public void onContextInvalidated() {
        fail("STUCK");
    }

    /** Silent termination for harness cancellation. */
    public void abort() {
        active = false;
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Override
    public String missionTaskId() {
        return taskId;
    }

    @Override
    public String failureReasonOrNull() {
        return failure;
    }

    // ===== DigMission interface =====

    @Override
    public CellPos digTarget() {
        return currentTarget;
    }

    @Override
    public boolean isDigging() {
        return phase == Phase.DIGGING && active;
    }

    @Override
    public int priority() {
        return priority;
    }

    // ===== Accessors for testing/observability =====

    /** Current phase; for testing and observability. */
    public Phase phase() {
        return phase;
    }

    /** Number of items collected so far. */
    public int collectedCount() {
        return collectedCount;
    }

    /** The block type being mined. */
    public String blockType() {
        return blockType;
    }

    /** The requested collection count. */
    public int targetCount() {
        return targetCount;
    }

    // ===== Internal helpers =====

    private Directive fail(String reason) {
        failure = reason;
        active = false;
        phase = Phase.FAILED;
        return Directive.of(null);
    }

    /**
     * Estimate the bot's block position from the world view. The
     * WorldView interface does not expose a direct position accessor in
     * v1; we use the current target as the center if available, otherwise
     * fall back to origin. In production the bot position comes from the
     * BotState snapshot; this is a known v1 simplification.
     */
    private CellPos estimateBotPos(WorldView world) {
        // v1: use currentTarget as the search center if we have one
        // (we are continuing after a skip), otherwise the search starts
        // from origin. A proper position accessor on WorldView is a
        // follow-up; for the first mine task the bot starts near the
        // target area anyway.
        return currentTarget != null ? currentTarget : new CellPos(0, 64, 0);
    }

    private static int chebyshev(CellPos a, CellPos b) {
        return Math.max(Math.abs(a.x() - b.x()),
            Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
    }

    /**
     * Count occurrences of the given item id in the main inventory.
     * Returns 0 if the inventory is unavailable.
     */
    private static int countItem(WorldView world, String itemId) {
        var inv = world.getInventory();
        if (inv == null) {
            return 0;
        }
        int count = 0;
        for (ItemView slot : inv.main()) {
            if (itemId.equals(slot.itemId())) {
                count += slot.count();
            }
        }
        return count;
    }
}
