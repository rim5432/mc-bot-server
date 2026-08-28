package com.mcbot.mcbotserver.adapter.rescue;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Reflex-owned rescue mission factory: called when an ESCAPE reflex
 * fires, reads the body state to decide which threat to escape
 * (lava shore vs water source), scans the world for the nearest
 * reachable target, and returns a GotoProcess to that cell.
 *
 * <p>Contract: see issue 0008 D2 (lava escape) and D5-revised (fire
 * find-water). The factory is the adapter-side half of the ESCAPE
 * handoff: the reflex notices the threat and names the kind, this
 * factory finds the concrete target cell and mints the mission. A
 * factory that finds no reachable target returns null, and the
 * controller degrades the ESCAPE to a FREEZE hold (park and
 * escalate) — no escape route means the harness must decide.
 *
 * <p>Two rescue kinds, selected by body state at call time:
 * <ul>
 * <li><b>Lava</b> ({@code body.isInLava()}): nearest non-liquid cell
 *     with a solid floor below — the shore. The pathing behavior
 *     handles ascent through the fluid column plus horizontal swim.</li>
 * <li><b>Fire</b> ({@code body.getRemainingFireTicks() > 0}): nearest
 *     walkable cell adjacent to a water block — water contact
 *     extinguishes fire (vanilla isInWaterRainOrBubble).</li>
 * </ul>
 * Lava takes precedence when both are true: 4 HP/tick outranks
 * 1 HP/sec, and reaching shore also leaves the fire source.
 *
 * <p>Implementation note: runs on the server tick thread (called from
 * BotController.runPipeline during the reflex preemption tick). The
 * scan is a brute-force radius cube — cheap at R=12 (17k cells,
 * trait reads are cached by the binding view). A BFS would be
 * more correct for "nearest reachable" but the pathing behavior
 * re-validates the route on its own ticks, so a nearest-by-distance
 * target is sufficient for v1.
 */
public final class RescueMissionFactory implements Supplier<BotProcess> {

    /** Scan radius in blocks for both rescue kinds. */
    private static final int SCAN_RADIUS = 12;

    /** Tick budget for a rescue mission; long enough for a 12-block
     *  swim/walk at the carrier's movement speed. */
    private static final long RESCUE_TIMEOUT_TICKS = 400;

    /** Routine-band priority for the rescue GotoProcess. */
    private static final int RESCUE_PRIORITY = 50;

    /**
     * Forward bias for shore selection: a shore cell in the bot's
     * facing direction is preferred by this many block-equivalents.
     * Value 2.0 means a shore 3 blocks forward beats a shore 1 block
     * behind (score 1 vs 3), but a shore 1 block behind beats a shore
     * 10 blocks forward (score 3 vs 8). Prevents the escape from
     * sending the bot backward into a mission-crossing loop while
     * still choosing the genuinely nearest shore in a free-fall
     * scenario.
     */
    private static final double FORWARD_BIAS = 2.0;

    private final WorldView view;
    private final BotBodyEntity body;
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param view the live world view for trait scanning; never null
     * @param body the carrier for state reads (isInLava, fire ticks);
     *             never null
     */
    public RescueMissionFactory(WorldView view, BotBodyEntity body) {
        this.view = view;
        this.body = body;
    }

    /**
     * Mint a rescue mission for the current body state, or null when
     * no reachable target exists within the scan radius.
     *
     * @return a fresh GotoProcess to the nearest safe cell, or null
     */
    @Override
    public BotProcess get() {
        CellPos origin = new CellPos(body.getBlockX(), body.getBlockY(), body.getBlockZ());
        CellPos target;
        if (body.isInLava()) {
            target = findNearestShore(origin);
        } else if (body.getRemainingFireTicks() > 0) {
            target = findNearestWater(origin);
        } else {
            return null;
        }
        if (target == null) {
            return null;
        }
        String taskId = "reflex-rescue-" + counter.incrementAndGet();
        return new GotoProcess(
                taskId, new GoalBlock(target), PriorityBands.requireLegal(RESCUE_PRIORITY), RESCUE_TIMEOUT_TICKS);
    }

    /** Nearest lava-adjacent dry cell in the scan cube. */
    private CellPos findNearestShore(CellPos origin) {
        return scanNearest(origin, this::isShore);
    }

    /**
     * Find the nearest water cell (liquid && !damaging) with a solid
     * floor below. Targeting the water cell itself (not an adjacent
     * dry cell) ensures the bot's AABB overlaps the fluid and
     * isInWaterRainOrBubble extinguishes the fire — an adjacent
     * dry cell leaves the bot burning.
     */
    private CellPos findNearestWater(CellPos origin) {
        return scanNearest(origin, this::isWaterCell);
    }

    /**
     * The shared scan skeleton the two finders differed only in the
     * cell predicate of: walk the SCAN_RADIUS cube, keep the
     * direction-aware best among predicate-passing cells.
     */
    private CellPos scanNearest(CellPos origin, java.util.function.Predicate<CellPos> predicate) {
        double[] fwd = forwardVector();
        CellPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    CellPos candidate = new CellPos(origin.x() + dx, origin.y() + dy, origin.z() + dz);
                    if (!predicate.test(candidate)) {
                        continue;
                    }
                    double score = directionAwareScore(origin, candidate, fwd);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Direction-aware score: Euclidean distance minus a forward bias.
     * A candidate in the bot's facing direction gets a lower (better)
     * score; one behind gets a higher (worse) score. The bias is
     * bounded so a genuinely near shore always beats a far one.
     */
    private double directionAwareScore(CellPos origin, CellPos candidate, double[] fwd) {
        double dx = candidate.x() - origin.x();
        double dz = candidate.z() - origin.z();
        double dist = candidate.distanceTo(origin);
        if (dist < 1e-6) {
            return 0;
        }
        double dot = (dx * fwd[0] + dz * fwd[1]) / dist;
        return dist - FORWARD_BIAS * dot;
    }

    /**
     * Bot's horizontal facing as a unit vector [fx, fz]. Minecraft
     * yaw: 0 = south (+z), -90 = east (+x).
     */
    private double[] forwardVector() {
        double yawRad = Math.toRadians(body.getYRot());
        return new double[] {-Math.sin(yawRad), Math.cos(yawRad)};
    }

    /**
     * A shore cell: not liquid, not damaging, passable (body can
     * occupy it), with a walkable top below (body can stand on the
     * floor).
     */
    private boolean isShore(CellPos cell) {
        if (!view.isLoaded(cell)) {
            return false;
        }
        BlockTraits traits = view.getBlockTraits(cell, ViewMode.LIVE);
        if (traits.liquid() || traits.damaging()) {
            return false;
        }
        if (!view.getCollisionShape(cell, ViewMode.LIVE).passable()) {
            return false;
        }
        CellPos below = new CellPos(cell.x(), cell.y() - 1, cell.z());
        if (!view.isLoaded(below)) {
            return false;
        }
        return view.getCollisionShape(below, ViewMode.LIVE).walkableTop();
    }

    /**
     * A water cell: liquid && !damaging (water, not lava), with a
     * solid floor below so the bot can stand in it (1-deep water).
     * The bot enters this cell to extinguish fire via
     * isInWaterRainOrBubble.
     */
    private boolean isWaterCell(CellPos cell) {
        if (!view.isLoaded(cell)) {
            return false;
        }
        BlockTraits traits = view.getBlockTraits(cell, ViewMode.LIVE);
        if (!traits.liquid() || traits.damaging()) {
            return false;
        }
        CellPos below = new CellPos(cell.x(), cell.y() - 1, cell.z());
        if (!view.isLoaded(below)) {
            return false;
        }
        return view.getCollisionShape(below, ViewMode.LIVE).walkableTop();
    }
}
