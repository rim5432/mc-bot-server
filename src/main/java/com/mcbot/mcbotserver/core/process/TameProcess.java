package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.process.Tame;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.tame.TameFoodCatalog;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * The harness-directed taming: one named tameable animal, chased and
 * fed its tame item until the world scan reports it tamed. The
 * sibling of {@link AttackProcess} with the verdict inverted - the
 * tamed fact riding the snapshot IS the completion verdict, the same
 * directly-judged shape as attack's corpse-sighting kill (ledger 40):
 * a sighting that flipped to tamed after engagement completes the
 * mission, instead of being inferred from absence. Terminal
 * semantics mirror attack deliberately: a target id that never
 * appears fails fast as NO_SUCH_ENTITY, absence past the grace
 * window fails TARGET_ESCAPED, leash and timeout behave as in
 * defend. The sharpenings are the typed refusals the tame task can
 * make that a fight cannot: the first sighting settles the species
 * (NOT_TAMEABLE), whether the animal already belongs to someone
 * (ALREADY_TAMED), and whether a tame item is even equipped
 * (NO_TAME_ITEM) - each refusing NOW instead of budget-staring at a
 * task that can never land. Press pacing stays the behavior tier's
 * craft ({@code TameBehavior}); the approach is the shared
 * GoalNear chase.
 *
 * <p>Contract: see boundaries.md decision 10 (no per-mob processes -
 * the target id is injected data) and decision 18's goto shape (task
 * rides CommandBus; the wire row lives in
 * harness-interaction.md).
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (light planner output)
public final class TameProcess extends MissionShell {

    /**
     * Entity scan radius. Matches {@link AttackProcess#SCAN_RADIUS}:
     * every directed task shares one perception envelope, and a
     * target beyond it is treated as escaped.
     */
    public static final double SCAN_RADIUS = 16.0;

    /** Shared envelope; canonical doc in {@link TargetTracker}. */
    public static final double LEASH_RADIUS = TargetTracker.LEASH_RADIUS;

    /** Shared envelope; canonical doc in {@link TargetTracker}. */
    public static final int TARGET_GRACE_TICKS = TargetTracker.TARGET_GRACE_TICKS;

    /**
     * Chase-goal range around the target cell. Same value and
     * rationale as {@link AttackProcess#GOAL_RANGE}: A* ends on the
     * first cell satisfying the predicate, so 2 keeps the body
     * outside overlap while staying inside the executor's
     * use-on-entity reach.
     */
    public static final int GOAL_RANGE = 2;

    /** Failure reason: the target id never appeared in any scan. */
    public static final String REASON_NO_TARGET = "NO_SUCH_ENTITY";

    /** Failure reason: engaged target left the leash radius. */
    public static final String REASON_LOST = "LOST_TARGET";

    /** Failure reason: the mission outlived its tick budget. */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** Failure reason: reflex preemption invalidated the context. */
    public static final String REASON_PREEMPTED = "PREEMPTED";

    /**
     * Failure reason: the target stayed absent from the scan past
     * the grace window - fled or removed, the bot cannot tell which.
     */
    public static final String REASON_ESCAPED = "TARGET_ESCAPED";

    /** Failure reason: the named species is not an item-tameable animal. */
    public static final String REASON_NOT_TAMEABLE = "NOT_TAMEABLE";

    /** Failure reason: the animal already carries an owner. */
    public static final String REASON_ALREADY_TAMED = "ALREADY_TAMED";

    /** Failure reason: no tame item for the species sits in the hotbar. */
    public static final String REASON_NO_TAME_ITEM = "NO_TAME_ITEM";

    /** Failure reason: the target died before the tame could land. */
    public static final String REASON_TARGET_DEAD = "TARGET_DEAD";

    /**
     * Failure reason: the target is in an aggressive state that blocks
     * item taming. In 1.20.1 only the wolf carries an anger mechanic
     * that gates {@code Wolf.mobInteract} (bone press falls through
     * when {@code isAngry()}); the snapshot carries the bit so the
     * process refuses now instead of budget-staring at presses that
     * never consume.
     */
    public static final String REASON_TARGET_ANGRY = "TARGET_ANGRY";

    private final String targetId;
    private final Supplier<CellPos> positionSource;

    private boolean succeeded;

    @Nullable
    private String failure;

    private final TargetTracker tracker = new TargetTracker();
    private Directive lastDirective;

    /**
     * Creates a directed taming mission.
     *
     * @param taskId         boundary-D task id for tracing; never
     *                       null or blank
     * @param targetId       {@link EntitySnapshot#id()} of the animal
     *                       to tame; never null or blank
     * @param priority       band-legal priority per PriorityBands
     * @param timeoutTicks   tick budget; positive
     * @param positionSource body cell accessor feeding the scan
     *                       center; never null
     */
    public TameProcess(
            String taskId,
            @Nullable String targetId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource) {
        super(taskId, priority, timeoutTicks);
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        this.targetId = targetId;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.lastDirective = Directive.of(new GoalNear(positionSource.get(), 1));
    }

    /**
     * The animal this mission was pointed at.
     *
     * @return the handed target id; never null or blank
     */
    public String targetId() {
        return targetId;
    }

    @Override
    public Directive onTick(WorldView world) {
        if (!live()) {
            return lastDirective;
        }
        if (budgetExpired()) {
            fail(REASON_TIMEOUT);
            return lastDirective;
        }

        CellPos position = positionSource.get();
        EntitySnapshot target = findById(world, position);
        if (target == null) {
            if (!tracker.engaged()) {
                // The id the harness named has never been in the
                // scan: refuse NOW instead of budget-staring. A
                // despawn between ls and submit is
                // indistinguishable from a typo - both deserve the
                // same fast, honest no.
                fail(REASON_NO_TARGET);
                return lastDirective;
            }
            if (tracker.graceSpent()) {
                fail(REASON_ESCAPED);
            }
            return lastDirective;
        }
        if (target.health() <= 0f) {
            fail(REASON_TARGET_DEAD);
            return lastDirective;
        }

        boolean firstSighting = !tracker.engaged();
        if (target.tamed()) {
            // The tamed fact is the completion verdict, attack's
            // corpse-sighting shape: after engagement it means OUR
            // presses landed; at first sight it means the animal
            // already belonged to someone.
            if (firstSighting) {
                fail(REASON_ALREADY_TAMED);
            } else {
                succeed();
            }
            return lastDirective;
        }
        tracker.sighted(target.pos());
        if (tracker.leashed(position)) {
            fail(REASON_LOST);
            return lastDirective;
        }
        if (firstSighting) {
            if (!TameFoodCatalog.isTameable(target.type())) {
                fail(REASON_NOT_TAMEABLE);
                return lastDirective;
            }
            if (target.angry()) {
                // An angry wolf's mobInteract falls through the bone
                // branch (Wolf.java:377 guards on !isAngry()), so every
                // press is a silent no-op - refuse now instead of
                // budget-staring. Only the wolf carries an anger gate in
                // 1.20.1; the snapshot bit is false for every other type.
                fail(REASON_TARGET_ANGRY);
                return lastDirective;
            }
            if (TameFoodCatalog.hotbarTameItemSlot(world.getInventory(), target.type()) < 0) {
                fail(REASON_NO_TAME_ITEM);
                return lastDirective;
            }
        }
        CellPos targetCell = tracker.targetCell();
        if (targetCell == null) {
            fail(REASON_NO_TARGET);
            return lastDirective;
        }
        lastDirective = new Directive(
                new GoalNear(targetCell, GOAL_RANGE), new Overrides(null, null, new Tame(targetId, target.type())));
        return lastDirective;
    }

    /**
     * Resolve the named entity inside the shared scan envelope.
     *
     * @param world    read-only world; never null
     * @param position scan center (the body cell); never null
     * @return the snapshot, or null when absent from the scan
     */
    @Nullable
    private EntitySnapshot findById(WorldView world, CellPos position) {
        List<EntitySnapshot> found = world.getEntities(position, SCAN_RADIUS, ViewMode.LIVE);
        for (EntitySnapshot e : found) {
            if (e.id().equals(targetId)) {
                return e;
            }
        }
        return null;
    }

    private void succeed() {
        deactivate();
        succeeded = true;
    }

    /**
     * Sticky termination - the defend guard: once a verdict is
     * recorded, later lifecycle callbacks (preemption after a
     * harness cancel) must not overwrite it.
     *
     * @param reason the machine-readable failure reason
     */
    private void fail(String reason) {
        if (live()) {
            deactivate();
            succeeded = false;
            failure = reason;
        }
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        // Execution weather, exactly as in attack: a SUCCEEDED chase
        // report means the body is next to the animal - where the
        // FEEDING starts, not a verdict. Scans, leash, and timeout
        // own every terminal decision.
    }

    @Override
    public void onLostControl(InterruptionContext c) {
        // Reflex supremacy (decision 9): parking does not end the
        // tame; resume() adjudicates on the next scan.
    }

    @Override
    public boolean resume(InterruptionContext c) {
        if (!live()) {
            return false;
        }
        // Blind-trust guard, the defend trick: spend all grace
        // credit so the very next scan decides.
        tracker.spendAllGrace();
        return true;
    }

    @Override
    public void onContextInvalidated() {
        fail(REASON_PREEMPTED);
    }

    /** Abort hook for harness cancellation; terminal state is sticky. */
    @Override
    public void abort() {
        fail("CANCELLED");
    }

    @Override
    public String displayName() {
        return "tame:" + taskId();
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Override
    @Nullable
    public String failureReasonOrNull() {
        return live() ? null : failure;
    }

    @Override
    public Map<String, String> verdictAttrs() {
        return Map.of("targetId", targetId);
    }
}
