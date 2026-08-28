package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Composite mining task (issue 0014): search for nearby blocks of a
 * given type, walk to each, mine it, walk over the drop, and repeat
 * until the requested number of blocks is broken. Multi-phase process
 * precedent: {@code DefendProcess} (issue 0007); dig mechanics ride
 * the {@link DigMission} claim path extracted from DigProcess.
 *
 * <p>Termination counts BLOCKS BROKEN, not inventory items (issue
 * 0014 section 3, review correction): a block's drop id routinely
 * differs from its block id (stone drops cobblestone, ores drop
 * raw items), so an inventory count keyed by the block id can never
 * rise and the mission would run to timeout. Drops are collected
 * best-effort by walking over the broken cell; drop-loss tracking is
 * deferred with issue 0014 section 6.
 *
 * <p>The process is side-effect-free (boundary B): it outputs
 * {@code Directive{GoalNear}} for movement and signals digging
 * through {@link DigMission}; the controller's mission-dig claim path
 * injects the actual {@code Intent.Dig} per tick. The bot's own
 * position is injected as a supplier (the assembly passes the body
 * pose) because WorldView deliberately exposes no self-position read.
 * World reads (block search, air poll) happen in {@link #onTick} via
 * the live {@link WorldView}.
 *
 * <p>State machine: IDLE → SEARCH → MOVING → DIGGING → COLLECTING →
 * (SEARCH | DONE), with FAILED as the terminal failure path.
 * Per-target failures (STUCK, move budget, dig budget, unloaded
 * mid-dig) skip that target and return to SEARCH rather than failing
 * the whole mission.
 *
 * <p>Contract: see boundaries.md section B (process tier is
 * side-effect-free) + issue 0014 §3 (MineProcess architecture).
 */
// contract: see boundaries.md section B + issue 0014 §3
// TooManyMethods exempted: the count is dominated by the BotProcess
// contract surface (interrupt quintet) plus per-field observability
// getters consumed by the gate tests; both belong here.
@SuppressWarnings("PMD.TooManyMethods")
public final class MineProcess extends MissionShell implements DigMission {

    /** One completed break, drained by the handler for BLOCK_BROKEN. */
    public record BlockBreak(CellPos pos, String blockId) {}

    /** Mission phases (package-private: offline tests assert these). */
    enum Phase {
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
    private static final int DEFAULT_PER_TARGET_DIG_BUDGET = 400;
    private static final int DEFAULT_PICKUP_WINDOW = 20;

    private final String blockType;
    private final int targetCount;
    private final int searchRadius;
    private final int perTargetMoveBudget;
    private final int perTargetDigBudget;
    private final int pickupWindow;
    private final Supplier<CellPos> botPosition;
    // Directive demands a non-null goal on every tick, including the
    // failure tick before any target exists; the position captured at
    // construction is the guaranteed anchor for that hold.
    private final CellPos initialPosition;

    private Phase phase = Phase.IDLE;
    private CellPos currentTarget;
    private int brokenCount;
    private long ticksInCurrentPhase;
    private boolean succeeded;
    private String failure;
    private final Set<CellPos> skipSet = new HashSet<>();
    private final ArrayDeque<BlockBreak> breaks = new ArrayDeque<>();

    /**
     * Creates a mine mission.
     *
     * @param taskId      the boundary-D task id; never null
     * @param blockType   the registry id of blocks to mine, e.g.
     *                    {@code minecraft:stone}; never null
     * @param targetCount how many blocks to break; positive
     * @param priority    arbiter seat priority (same scale as goto/dig)
     * @param timeoutTicks mission budget; positive
     * @param botPosition live body-position accessor used as the search
     *                    center; must not be null
     */
    public MineProcess(
            String taskId,
            String blockType,
            int targetCount,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> botPosition) {
        super(taskId, priority, timeoutTicks);
        requireValid(blockType, targetCount, botPosition);
        this.blockType = blockType;
        this.targetCount = targetCount;
        this.searchRadius = DEFAULT_SEARCH_RADIUS;
        this.perTargetMoveBudget = DEFAULT_PER_TARGET_MOVE_BUDGET;
        this.perTargetDigBudget = DEFAULT_PER_TARGET_DIG_BUDGET;
        this.pickupWindow = DEFAULT_PICKUP_WINDOW;
        this.botPosition = botPosition;
        this.initialPosition = botPosition.get();
    }

    /** Rejects constructor arguments before any field is assigned. */
    private static void requireValid(String blockType, int targetCount, Supplier<CellPos> botPosition) {
        requireText(blockType, "blockType");
        requirePositive(targetCount, "targetCount");
        requireNonNullPosition(botPosition);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNullPosition(Supplier<CellPos> supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("botPosition must not be null");
        }
        if (supplier.get() == null) {
            throw new IllegalArgumentException("botPosition must yield a non-null position");
        }
    }

    @Override
    public String displayName() {
        return "mine:" + taskId();
    }

    @Override
    public Directive onTick(WorldView world) {
        if (!live()) {
            // Terminal hold: the arbiter retires us on the next lap.
            return holdDirective();
        }
        if (budgetExpired()) {
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
            case DONE, FAILED -> holdDirective();
        };
    }

    /**
     * SEARCH phase: cube-scan the radius around the live bot position
     * for target blocks, pick the nearest non-skipped one, transition
     * to MOVING. Unloaded cells read as null and are skipped. If no
     * candidate remains, fail honestly (a mission whose supply is
     * exhausted must not wander).
     */
    private Directive doSearch(WorldView world) {
        CellPos center = botPosition.get();
        if (center == null) {
            return fail("no bot position");
        }
        List<CellPos> candidates = new ArrayList<>();
        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    CellPos pos = new CellPos(center.x() + dx, center.y() + dy, center.z() + dz);
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
            return fail("no " + blockType + " within " + searchRadius + " (" + skipSet.size() + " skipped)");
        }
        candidates.sort(Comparator.comparingInt(p -> chebyshev(center, p)));
        currentTarget = candidates.get(0);
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
     * injects aim+dig claims. Poll the target cell; when it turns to
     * air, count the break, record it for the BLOCK_BROKEN disclosure,
     * and walk over the cell for the drop (COLLECTING). A dig budget
     * bounds unbreakable or too-slow targets; unloaded mid-dig skips
     * the target instead of failing the mission.
     */
    private Directive doDigging(WorldView world) {
        if (ticksInCurrentPhase >= perTargetDigBudget) {
            skipSet.add(currentTarget);
            phase = Phase.SEARCH;
            ticksInCurrentPhase = 0;
            return doSearch(world);
        }
        BlockSnapshot now = world.getBlock(currentTarget, ViewMode.LIVE);
        if (now == null) {
            skipSet.add(currentTarget);
            phase = Phase.SEARCH;
            ticksInCurrentPhase = 0;
            return doSearch(world);
        }
        if (BlockSnapshot.AIR.equals(now.blockId())) {
            brokenCount++;
            breaks.add(new BlockBreak(currentTarget, blockType));
            phase = Phase.COLLECTING;
            ticksInCurrentPhase = 0;
            // Walk over the broken cell: vanilla auto-pickup collects
            // the drop on contact (best-effort, not a gate).
            return Directive.of(new GoalNear(currentTarget, 1));
        }
        // Hold position while digging.
        return Directive.of(new GoalNear(currentTarget, 2));
    }

    /**
     * COLLECTING phase: stand on the broken cell for the pickup window
     * so vanilla auto-pickup can collect the drop, then decide: target
     * count reached → DONE, otherwise search for the next target.
     */
    private Directive doCollecting(WorldView world) {
        if (ticksInCurrentPhase < pickupWindow) {
            return Directive.of(new GoalNear(currentTarget, 1));
        }
        if (brokenCount >= targetCount) {
            succeeded = true;
            deactivate();
            phase = Phase.DONE;
            return holdDirective();
        }
        // The cell reads air now, so a later search will not re-select
        // it; skipSet only needs the failure paths.
        phase = Phase.SEARCH;
        ticksInCurrentPhase = 0;
        return doSearch(world);
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        if (!live()) {
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
    public void onLostControl(InterruptionContext ctx) {
        // Keep state; resume revalidates through world reads (boundary
        // B resume contract - same shape as DigProcess).
    }

    @Override
    public boolean resume(InterruptionContext ctx) {
        // Break progress resetting on eviction is vanilla-parity; the
        // target cell is re-read every tick, so a mid-dig world change
        // is caught by the DIGGING poll rather than by resume.
        return live();
    }

    @Override
    public void onContextInvalidated() {
        fail("STUCK");
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
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
        return phase == Phase.DIGGING && live();
    }

    // ===== Accessors for testing/observability =====

    /** Current phase; for tests and observability. */
    Phase phase() {
        return phase;
    }

    /**
     * Number of blocks broken so far.
     *
     * @return the termination counter value
     */
    public int brokenCount() {
        return brokenCount;
    }

    /**
     * The block type being mined.
     *
     * @return the namespaced block id; never null
     */
    public String blockType() {
        return blockType;
    }

    /**
     * The requested break count.
     *
     * @return the positive termination target
     */
    public int targetCount() {
        return targetCount;
    }

    /**
     * Drain one recorded break for the BLOCK_BROKEN disclosure, oldest
     * first. The handler polls this on its tick sweep.
     *
     * @return the oldest unreported break; null when none pending
     */
    public BlockBreak pollBreak() {
        return breaks.poll();
    }

    // ===== Internal helpers =====

    private Directive fail(String reason) {
        failure = reason;
        deactivate();
        phase = Phase.FAILED;
        return holdDirective();
    }

    /**
     * Valid goal for holds and terminal ticks: the current target if
     * one exists, otherwise the position captured at construction.
     * Directive rejects a null goal, and a failure tick must not
     * throw - that would trip the controller's crash latch over a
     * mission-level failure (ADR-0005).
     */
    private Directive holdDirective() {
        CellPos anchor = currentTarget != null ? currentTarget : initialPosition;
        return Directive.of(new GoalNear(anchor, 2));
    }

    private static int chebyshev(CellPos a, CellPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
    }
}
