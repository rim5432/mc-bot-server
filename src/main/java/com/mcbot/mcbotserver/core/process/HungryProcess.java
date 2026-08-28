package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.FoodCatalog;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Fish;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
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
// TooManyMethods exempted: the forage phase machine is deliberately
// one named method per phase and per transition gate (assess/forage/
// hunt/collect/fish plus their tick halves) - the split IS the
// readability fix; merging phases back would reintroduce the CC wall.
@SuppressWarnings("PMD.TooManyMethods")
public final class HungryProcess extends MissionShell implements DigMission {

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

    /** Water scan half-width when the fish strategy assesses (blocks). */
    private static final int WATER_SCAN_RADIUS = 8;

    private static final String WATER_ID = "minecraft:water";

    private static final String ROD_ID = "minecraft:fishing_rod";

    private final Supplier<CellPos> positionSource;
    private final Supplier<CellPos> forageTarget;
    private final FoodCatalog catalog;
    private final Set<String> foodTypes;

    private boolean succeeded;
    private String failure;
    private boolean digging;
    private Phase phase = Phase.ASSESS;
    private String preyId;
    private CellPos preyCell;
    private CellPos collectCell;
    private CellPos waterCell;
    private int collectTicks;
    private Directive lastDirective = Directive.of(new GoalNear(new CellPos(0, 64, 0), 0));

    private enum Phase {
        ASSESS,
        FORAGE,
        HUNT,
        COLLECT,
        FISH
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
        super(taskId, priority, timeoutTicks);
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.forageTarget = Objects.requireNonNull(forageTarget, "forageTarget");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.foodTypes = Set.copyOf(foodTypes);
    }

    @Override
    public Directive onTick(WorldView world) {
        if (!live()) {
            return lastDirective;
        }
        if (foodNowCarried(world)) {
            succeed();
            return lastDirective;
        }
        if (budgetExpired()) {
            fail(REASON_TIMEOUT);
            return lastDirective;
        }
        tickPhase(world);
        return lastDirective;
    }

    /**
     * The success gate: any edible item anywhere in main inventory
     * ends the forage - the eat reflex takes over from there.
     */
    private boolean foodNowCarried(WorldView world) {
        InventoryView inventory = world.getInventory();
        for (ItemView item : inventory.main()) {
            if (!item.isEmpty() && catalog.facts(item.itemId()) != null) {
                return true;
            }
        }
        return false;
    }

    /** Dispatches the phase machine for this tick. */
    private void tickPhase(WorldView world) {
        CellPos body = positionSource.get();
        switch (phase) {
            case ASSESS -> assess(world, body);
            case FORAGE -> forageTick(body);
            case HUNT -> huntTick(world, body);
            case COLLECT -> collectTick();
            case FISH -> fishTick(world);
            default -> throw new IllegalStateException("unhandled phase: " + phase);
        }
    }

    /** ASSESS: forage outranks fish outranks hunt (0010 section 4.3). */
    private void assess(WorldView world, CellPos body) {
        if (forageTarget.get() != null) {
            phase = Phase.FORAGE;
            forageTick(body);
            return;
        }
        // Fish sits between forage and hunt (0010 section 4.3, as
        // implemented): no chase and no combat exposure, so it beats
        // hunting whenever the gear and open water both exist.
        if (hasRod(world.getInventory())) {
            CellPos water = findWater(world, body);
            if (water != null) {
                waterCell = water;
                phase = Phase.FISH;
                lastDirective = new Directive(new GoalNear(water, 1), new Overrides(null, new Fish(water)));
                return;
            }
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

    /**
     * FISH: hold the water's edge and let the fish behavior work the
     * rod; revalidate the water cell so an evaporated pond re-enters
     * assessment instead of fishing a dry hole.
     *
     * @param world read surface for the water revalidation; never null
     */
    private void fishTick(WorldView world) {
        BlockSnapshot snap = world.getBlock(waterCell, ViewMode.LIVE);
        if (snap == null || !WATER_ID.equals(snap.blockId())) {
            phase = Phase.ASSESS;
            return;
        }
        lastDirective = new Directive(new GoalNear(waterCell, 1), new Overrides(null, new Fish(waterCell)));
    }

    /**
     * First fishing rod in the main inventory.
     *
     * @param inventory the live inventory snapshot; never null
     * @return true when a rod is carried
     */
    private static boolean hasRod(InventoryView inventory) {
        for (ItemView item : inventory.main()) {
            if (!item.isEmpty() && ROD_ID.equals(item.itemId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nearest water cell in a flat-radius box around the body. ASSESS
     * is a once-per-episode scan, so the small cube sweep is cheap;
     * the first hit wins (any water within reach serves the cast).
     *
     * @param world read surface; never null
     * @param body  the body's cell; never null
     * @return a water cell, or null when none is in range
     */
    private static CellPos findWater(WorldView world, CellPos body) {
        for (int dy = 0; dy >= -2; dy--) {
            for (int dx = -WATER_SCAN_RADIUS; dx <= WATER_SCAN_RADIUS; dx++) {
                for (int dz = -WATER_SCAN_RADIUS; dz <= WATER_SCAN_RADIUS; dz++) {
                    CellPos probe = new CellPos(body.x() + dx, body.y() + dy, body.z() + dz);
                    BlockSnapshot snap = world.getBlock(probe, ViewMode.LIVE);
                    if (snap != null && WATER_ID.equals(snap.blockId())) {
                        return probe;
                    }
                }
            }
        }
        return null;
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
    public void onExecutionReport(com.mcbot.mcbotserver.api.process.ExecutionReport report) {
        // Walk weather never decides the forage: the food check and
        // the tick budget own the verdict.
    }

    @Override
    public String displayName() {
        return "hungry:" + taskId();
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
                    case FISH -> "fish";
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
        deactivate();
        succeeded = true;
        digging = false;
    }

    private void fail(String reason) {
        deactivate();
        failure = reason;
        digging = false;
    }
}
