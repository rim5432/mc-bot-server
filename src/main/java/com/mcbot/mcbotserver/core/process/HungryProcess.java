package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.inventory.FoodCatalog;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * HungryProcess, the 0010 section 4.2 state machine (forage + hunt
 * strategies): ASSESS picks the first viable strategy - a sensed
 * berry bush walks-and-digs (forage), else the nearest passive food
 * mob is engaged with the same directive vocabulary DefendProcess
 * uses (hunt; CombatBehavior does the killing, no weapon required),
 * and a vanished prey walks to its last cell for pickup (collect).
 * Every tick checks inventory first: food landed means FOOD_ACQUIRED
 * and the EAT reflex owns chewing from there. Nothing viable, or a
 * burned budget, is FOOD_STRATEGY_EXHAUSTED - the section 5
 * escalation contract's structured failure reaching the harness.
 *
 * <p>Fish stays queued (rod-cast timing behavior, section 7).
 *
 * <p>Contract: see boundaries.md section B (processes are
 * side-effect-free) and issue 0010. Runs on the server tick thread
 * only.
 */
public final class HungryProcess implements BotProcess, TerminalMission, DigMission {

    /** Failure when no strategy was viable or the budget burned (0010 section 5). */
    public static final String REASON_EXHAUSTED = "FOOD_STRATEGY_EXHAUSTED";

    /** Failure when the tick budget ran out. */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** Dig reach: the bush breaks once the body stands next to it. */
    private static final double DIG_REACH = 4.0;

    /** Entity scan radius for prey (0010 D2, the threat-scan radius). */
    private static final int SCAN_RADIUS = 32;

    /** Ticks spent walking to the prey's last cell hoping for drops. */
    private static final int COLLECT_TICKS = 60;

    private final String taskId;
    private final int priority;
    private final long timeoutTicks;
    private final Supplier<CellPos> positionSource;
    private final Supplier<CellPos> forageTarget;
    private final FoodCatalog catalog;
    private final Set<String> foodTypes;

    private long ticksUsed;
    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private boolean digging;
    private Phase phase = Phase.ASSESS;
    private String preyId;
    private CellPos preyCell;
    private CellPos collectCell;
    private int collectTicks;
    private Directive lastDirective = Directive.of(new GoalNear(new CellPos(0, 64, 0), 0));

    private enum Phase {
        ASSESS,
        FORAGE,
        HUNT,
        COLLECT
    }

    /**
     * Creates the acquisition mission.
     *
     * @param taskId         boundary-D task id for tracing; never
     *                       null or blank
     * @param priority       band-legal priority
     * @param timeoutTicks   tick budget; positive
     * @param positionSource body cell accessor; never null
     * @param forageTarget   sensed bush cell supplier (null result =
     *                       no bush in range); never null
     * @param catalog        food classification; never null
     * @param foodTypes      entity types worth hunting; never null
     * @throws IllegalArgumentException on blank id or bad budget
     */
    public HungryProcess(
            String taskId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource,
            Supplier<CellPos> forageTarget,
            FoodCatalog catalog,
            Set<String> foodTypes) {
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
        this.foodTypes = Set.copyOf(foodTypes);
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
            return lastDirective;
        }
        ticksUsed++;
        InventoryView inventory = world.getInventory();
        for (ItemView item : inventory.main()) {
            if (!item.isEmpty() && catalog.facts(item.itemId()) != null) {
                succeed();
                return lastDirective;
            }
        }
        if (ticksUsed >= timeoutTicks) {
            fail(REASON_TIMEOUT);
            return lastDirective;
        }
        CellPos body = positionSource.get();
        switch (phase) {
            case ASSESS -> assess(world, body);
            case FORAGE -> forageTick(body);
            case HUNT -> huntTick(world, body);
            case COLLECT -> collectTick();
            default -> throw new IllegalStateException("unhandled phase: " + phase);
        }
        return lastDirective;
    }

    /** ASSESS: forage outranks hunt (0010 section 4.3 - fastest path). */
    private void assess(WorldView world, CellPos body) {
        if (forageTarget.get() != null) {
            phase = Phase.FORAGE;
            forageTick(body);
            return;
        }
        EntitySnapshot prey = scanPrey(world, body, null);
        if (prey == null) {
            fail(REASON_EXHAUSTED);
            return;
        }
        preyId = prey.id();
        preyCell = prey.pos();
        phase = Phase.HUNT;
        lastDirective = new Directive(new GoalNear(preyCell, 2), new Overrides(new Attack(preyId)));
    }

    private void forageTick(CellPos body) {
        CellPos target = forageTarget.get();
        if (target == null) {
            // Bush vanished between assessment and arrival: fall
            // through to a hunt attempt rather than dying hungry.
            phase = Phase.ASSESS;
            return;
        }
        double dist = Math.hypot(target.x() - body.x(), target.z() - body.z());
        digging = dist <= DIG_REACH;
        lastDirective = Directive.of(new GoalNear(target, 1));
    }

    private void huntTick(WorldView world, CellPos body) {
        EntitySnapshot prey = scanPrey(world, body, preyId);
        if (prey != null) {
            preyCell = prey.pos();
            lastDirective = new Directive(new GoalNear(preyCell, 2), new Overrides(new Attack(preyId)));
            return;
        }
        // Prey absent from the scan: dead (drops at the last cell) or
        // fled - the bot cannot tell which, so it walks the last cell
        // and lets pickup decide (issue 0006's honest-uncertainty
        // shape; a false EXHAUSTED is cheap, a false success is not).
        collectCell = preyCell;
        phase = Phase.COLLECT;
        lastDirective = Directive.of(new GoalNear(collectCell, 0));
    }

    private void collectTick() {
        collectTicks++;
        if (collectTicks >= COLLECT_TICKS) {
            fail(REASON_EXHAUSTED);
            return;
        }
        lastDirective = Directive.of(new GoalNear(collectCell, 0));
    }

    @Override
    public void onLostControl(InterruptionContext context) {
        // Parked mid-acquisition: state stays intact; the per-tick
        // food check and budget own the verdict (ADR-0003 section 3).
    }

    @Override
    public boolean resume(InterruptionContext context) {
        // Revalidate: prey and bush may be gone while parked - the
        // scans re-derive both; a stale target must not mint a ghost
        // walk or a ghost fight.
        return true;
    }

    @Override
    public void onContextInvalidated() {
        // Evicted for good: the mission ends unfulfilled - the ACQUIRE
        // reflex re-triggers on the next hungry tick.
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
        if (failure == null) {
            return Map.of();
        }
        String strategy =
                switch (phase) {
                    case FORAGE -> "forage";
                    case HUNT, COLLECT -> "hunt";
                    default -> "none";
                };
        return Map.of("reason", failure, "strategy", strategy);
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

    /**
     * One scan for both hunt queries: with an id, the first matching
     * prey (id + type); without, the nearest food mob.
     */
    private EntitySnapshot scanPrey(WorldView world, CellPos center, String id) {
        EntitySnapshot nearest = null;
        for (EntitySnapshot e : world.getEntities(center, SCAN_RADIUS, ViewMode.LIVE)) {
            if (!foodTypes.contains(e.type())) {
                continue;
            }
            if (id == null) {
                if (nearest == null || center.distanceTo(e.pos()) < center.distanceTo(nearest.pos())) {
                    nearest = e;
                }
            } else if (e.id().equals(id)) {
                return e;
            }
        }
        return nearest;
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
