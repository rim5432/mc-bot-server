package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.inventory.FoodCatalog;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * HungryProcess, consumption-side trigger (issue 0010 sections 4-5,
 * v1 = forage only per section 7's minimum viable slice): walk to the
 * sensed berry bush, dig it through the DigMission claim injection,
 * let GroundPickup vacuum the drops, and complete the moment food
 * lands in inventory - the EAT reflex owns chewing from there. No
 * bush in sensor range is FOOD_STRATEGY_EXHAUSTED: the one-attempt
 * forage budget (section 4.5) burns and the verdict reaches the
 * harness as the escalation contract's structured failure.
 *
 * <p>Wiring note (2026-08-27): the process and sensor are landed and
 * offline-gated; the trigger - a third reflex-owned seat - is the
 * promotion point the arch-drift no-generalization ruling reserved
 * ("seat dispatch at the third seat") and lands as its own change.
 *
 * <p>Contract: see boundaries.md section B (processes are
 * side-effect-free) and issue 0010. Runs on the server tick thread
 * only.
 */
public final class HungryProcess implements BotProcess, TerminalMission, DigMission {

    /** Failure when no forage target was sensed (0010 section 5). */
    public static final String REASON_EXHAUSTED = "FOOD_STRATEGY_EXHAUSTED";

    /** Failure when the tick budget ran out. */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** Dig reach: the bush breaks once the body stands next to it. */
    private static final double DIG_REACH = 4.0;

    private final String taskId;
    private final int priority;
    private final long timeoutTicks;
    private final Supplier<CellPos> positionSource;
    private final Supplier<CellPos> forageTarget;
    private final FoodCatalog catalog;

    private long ticksUsed;
    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private boolean digging;

    /**
     * Creates the forage mission.
     *
     * @param taskId         boundary-D task id for tracing; never
     *                       null or blank
     * @param priority       band-legal priority
     * @param timeoutTicks   tick budget; positive
     * @param positionSource body cell accessor; never null
     * @param forageTarget   sensed bush cell supplier (null result =
     *                       nothing in range); never null
     * @param catalog        food classification; never null
     * @throws IllegalArgumentException on blank id or bad budget
     */
    public HungryProcess(
            String taskId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource,
            Supplier<CellPos> forageTarget,
            FoodCatalog catalog) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.forageTarget = Objects.requireNonNull(forageTarget, "forageTarget");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Directive onTick(WorldView world) {
        if (!active) {
            return Directive.of(new GoalNear(positionSource.get(), 0));
        }
        ticksUsed++;
        if (carriesFood(world)) {
            succeed();
            return Directive.of(new GoalNear(positionSource.get(), 0));
        }
        if (ticksUsed >= timeoutTicks) {
            fail(REASON_TIMEOUT);
            return Directive.of(new GoalNear(positionSource.get(), 0));
        }
        CellPos target = forageTarget.get();
        if (target == null) {
            // One-attempt forage budget: no sensed bush this mission
            // is exhaustion, not a reason to rescan forever.
            fail(REASON_EXHAUSTED);
            return Directive.of(new GoalNear(positionSource.get(), 0));
        }
        CellPos body = positionSource.get();
        double dist = Math.hypot(target.x() - body.x(), target.z() - body.z());
        digging = dist <= DIG_REACH;
        return Directive.of(new GoalNear(target, 1));
    }

    @Override
    public void onLostControl(InterruptionContext context) {
        // Parked mid-forage: the rescan-on-resume rule below
        // revalidates the bush; state stays intact (ADR-0003 section 3).
    }

    @Override
    public boolean resume(InterruptionContext context) {
        // Revalidate: the bush may be gone (dug by someone else) while
        // parked - a stale target must not mint a ghost walk.
        return true;
    }

    @Override
    public void onContextInvalidated() {
        // Evicted for good: the mission ends unfulfilled - the EAT
        // reflex will re-trigger acquisition on the next hungry
        // tick if the seat wiring resubmits.
        fail(REASON_EXHAUSTED);
    }

    @Override
    public void onExecutionReport(com.mcbot.mcbotserver.api.behavior.ExecutionReport report) {
        // Walk weather never decides the forage: the food check and
        // the tick budget own the verdict.
    }

    @Override
    public String displayName() {
        return "hungry:" + taskId;
    }

    @Override
    public String missionTaskId() {
        return taskId;
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Override
    public String failureReasonOrNull() {
        return failure;
    }

    @Override
    public Map<String, String> verdictAttrs() {
        return failure != null ? Map.of("reason", failure, "strategy", "forage") : Map.of();
    }

    @Override
    public CellPos digTarget() {
        if (!digging) {
            throw new IllegalStateException("digTarget requires the digging phase");
        }
        return forageTarget.get();
    }

    @Override
    public boolean isDigging() {
        return digging;
    }

    private boolean carriesFood(WorldView world) {
        InventoryView inventory = world.getInventory();
        for (ItemView item : inventory.main()) {
            if (!item.isEmpty() && catalog.facts(item.itemId()) != null) {
                return true;
            }
        }
        return false;
    }

    private void succeed() {
        active = false;
        succeeded = true;
        digging = false;
    }

    private void fail(String reason) {
        active = false;
        failure = reason;
        digging = false;
    }
}
