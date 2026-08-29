package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.pathing.Movement;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stage-1 move set: walk, diagonal, step-up and drop - the smallest
 * vocabulary that makes flat ground, single steps and small ledges
 * searchable. Grow per workplan; the A* engine consumes this through
 * {@link MoveGraph} and never learns individual move types.
 *
 * <p>Viability rules (all reads via WorldView, LIVE mode):
 *
 * <ul>
 * <li>passable(cell): the cell's CollisionShape is body-clear
 * (EMPTY, or PARTIAL with top below STEP_UP_REACH). The shape is the
 * source of truth, the block id is not consulted (decision 19). Cells
 * the adapter does not annotate fall through to the WorldView's
 * safe-default, which is FULL_CUBE - blocking, not traversable.</li>
 * <li>supported(cell): the cell below has a walkable top
 * ({@link CollisionShape#walkableTop()}).</li>
 * <li>standable(cell): the body fits at the cell (foot + head
 * passable) and the cell below is walkable.</li>
 * <li>swimmable(cell): the cell's traits say liquid (decision 19:
 * "is this water?" is a property the empty collision shape cannot
 * infer). Water cells are passable but never standable, so they are
 * invisible to Walk/JumpUp - Swim and SwimUp exist for them.</li>
 * </ul>
 *
 * <p>Non-geometric block properties (climbable / liquid / damaging)
 * live in {@link com.mcbot.mcbotserver.api.world.BlockTraits} and are
 * consulted by the swim and climb sets; the trait is the property the
 * empty collision shape cannot infer (decision 19).
 *
 * <p>Decision 19a gate (swim): the full physical precondition is
 * derivable from Shape plus Traits - passability from the (empty)
 * collision box, swimmability from the liquid trait. No id table.
 */
// contract: see boundaries.md decisions 7/8 + 17a + 19
public final class BasicMoves {

    private BasicMoves() {}

    /**
     * All candidate movements leaving one cell.
     *
     * @param src the expansion cell; never null
     * @return unsorted candidate edges; viability not yet checked;
     *         never null
     */
    public static List<Movement> from(CellPos src) {
        Objects.requireNonNull(src, "src");
        List<Movement> out = new ArrayList<>();
        for (Direction d : Direction.values()) {
            out.add(new Walk(src, new CellPos(src.x() + d.dx, src.y(), src.z() + d.dz)));
            out.add(new JumpUp(src, new CellPos(src.x() + d.dx, src.y() + 1, src.z() + d.dz)));
            out.add(new Swim(src, new CellPos(src.x() + d.dx, src.y(), src.z() + d.dz)));
            out.add(new SwimUp(src, new CellPos(src.x() + d.dx, src.y() + 1, src.z() + d.dz)));
            out.add(new SwimDown(src, new CellPos(src.x() + d.dx, src.y() - 1, src.z() + d.dz)));
            for (int k = 1; k <= MAX_DROP; k++) {
                out.add(new Drop(src, new CellPos(src.x() + d.dx, src.y() - k, src.z() + d.dz), k));
            }
        }
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                out.add(new Diagonal(src, new CellPos(src.x() + dx, src.y(), src.z() + dz)));
            }
        }
        // Vertical-only water descent: straight down inside a water
        // column. Mirrors ClimbUp's vertical-only shape.
        out.add(new SwimDown(src, new CellPos(src.x(), src.y() - 1, src.z())));
        // Vertical climb: trait-driven, no horizontal component. Generated
        // from every cell; isViable refuses when the source is not
        // climbable. Mirrors SwimUp's vertical-only shape.
        out.add(new ClimbUp(src, new CellPos(src.x(), src.y() + 1, src.z())));
        return out;
    }

    /** Longest vertical drop the set considers walkable. */
    public static final int MAX_DROP = 3;

    private enum Direction {
        N(0, -1),
        S(0, 1),
        E(1, 0),
        W(-1, 0);

        final int dx;
        final int dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    /**
     * Body-passability from the cell's collision shape. Replaces the
     * Stage-0 {@code isAir} check: the shape's box tells us whether
     * the body can sweep through, and a partial block (slab, fence)
     * is correctly half-passable without an id check.
     *
     * @param world the view; must not be null
     * @param cell  the cell to test; must not be null
     * @return true when the cell is body-passable
     */
    private static boolean passable(WorldView world, CellPos cell) {
        return world.getCollisionShape(cell, ViewMode.LIVE).passable();
    }

    /**
     * Whether the cell below has a walkable top. Full cubes always;
     * partial blocks only when their top rises to
     * {@link CollisionShape#STANDABLE_THRESHOLD} and spans at least
     * {@link CollisionShape#BODY_WIDTH} on both horizontal axes (a
     * half-slab top qualifies; a plate is too low; a fence post is
     * too thin to balance on).
     *
     * @param world the view; must not be null
     * @param cell  the cell whose floor we test; must not be null
     * @return true when the cell below can hold a body
     */
    private static boolean supported(WorldView world, CellPos cell) {
        return world.getCollisionShape(new CellPos(cell.x(), cell.y() - 1, cell.z()), ViewMode.LIVE)
                .walkableTop();
    }

    // Package-visible so PlanSmoother's corridor check reuses the
    // exact same body-fit predicate the move viability uses.
    static boolean standable(WorldView world, CellPos cell) {
        return passable(world, cell)
                && passable(world, new CellPos(cell.x(), cell.y() + 1, cell.z()))
                && supported(world, cell);
    }

    /**
     * Whether the cell holds a swimmable fluid. Reads the liquid
     * trait, never the shape - water's collision box is empty, which
     * is exactly the case the shape cannot speak to (decision 19).
     *
     * @param world the view; must not be null
     * @param cell  the cell to test; must not be null
     * @return true when the traits registry marks the cell liquid
     */
    private static boolean liquid(WorldView world, CellPos cell) {
        return world.getBlockTraits(cell, ViewMode.LIVE).liquid();
    }

    /**
     * Whether the cell carries the climbable trait (ladder, vine,
     * weeping/twisting vines). Reads the trait, never the shape — a
     * ladder's collision box is empty above the rung, so the shape
     * cannot say "this cell is climbable" (decision 19).
     *
     * @param world the view; must not be null
     * @param cell  the cell to test; must not be null
     * @return true when the traits registry marks the cell climbable
     */
    private static boolean climbable(WorldView world, CellPos cell) {
        return world.getBlockTraits(cell, ViewMode.LIVE).climbable();
    }

    record Walk(CellPos source, CellPos destination) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.0;
        }
    }

    record Diagonal(CellPos source, CellPos destination) implements Movement {

        /**
         * Body-passability for the corner sweep. Reads the
         * {@link com.mcbot.mcbotserver.api.world.CollisionShape} the
         * adapter installed; the box's top is the source of truth
         * for whether the swept body fits.
         *
         * @param world the view; must not be null
         * @param cell  the cell to test; must not be null
         * @return true when the cell is body-passable
         */
        private static boolean passable(WorldView world, CellPos cell) {
            return world.getCollisionShape(cell, ViewMode.LIVE).passable();
        }

        @Override
        public boolean isViable(WorldView world) {
            int dx = destination.x() - source.x();
            int dz = destination.z() - source.z();
            // Foot-level corner sweep: the standard 1x1 grid answer
            // for a point body. The hitbox at z=0.5 / x=1.0 is
            // contained inside the destination cell; cells (1, 0)
            // and (0, 1) carry the swept area between them.
            boolean edgeA = passable(world, new CellPos(source.x() + dx, source.y(), source.z()));
            boolean edgeB = passable(world, new CellPos(source.x(), source.y(), source.z() + dz));
            // Head-level corner sweep: the player is 1.8 tall, so the
            // swept body also touches the cells one level up. Without
            // this check, a 1-cell obstruction directly above a
            // foot-clear corner would let the diagonal through and
            // the head would clip the obstruction on the next physics
            // step. Verified by BasicMovesDiagonalTest.
            boolean edgeAhead = passable(world, new CellPos(source.x() + dx, source.y() + 1, source.z()));
            boolean edgeBhead = passable(world, new CellPos(source.x(), source.y() + 1, source.z() + dz));
            return edgeA && edgeB && edgeAhead && edgeBhead && standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.5;
        }
    }

    /**
     * Deliberate jump onto one higher standable cell. Named for the
     * executor input it demands - a real jump thrust each tick the
     * waypoint is above the body (issue 0004 F1: vanilla "climb" is
     * reserved for a future trait-driven ladder/vine Movement).
     */
    record JumpUp(CellPos source, CellPos destination) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 2.0;
        }
    }

    record Drop(CellPos source, CellPos destination, int depth) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            for (int i = 1; i < depth; i++) {
                CellPos mid = new CellPos(destination.x(), source.y() - i, destination.z());
                if (!passable(world, mid)) {
                    return false;
                }
            }
            // Landing in liquid needs no floor: the fluid holds the
            // body. Landing on land still demands a walkable top.
            return standable(world, destination) || liquid(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.0 + depth * 1.5;
        }
    }

    /**
     * Horizontal swim, including stepping off a shore into water at
     * the same level: the destination is liquid (passable by its
     * empty shape, held by the fluid, never standable). The source
     * may be dry - entering water is deliberate here; leaving water
     * onto land is Walk/JumpUp's job and needs no new vocabulary.
     */
    record Swim(CellPos source, CellPos destination) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return passable(world, destination) && liquid(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.5;
        }
    }

    /** Vertical swim: treading up one cell inside a water column. */
    record SwimUp(CellPos source, CellPos destination) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return liquid(world, source) && passable(world, destination) && liquid(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 2.0;
        }
    }

    /**
     * Vertical or diagonal descent inside a water column. The source
     * must be liquid (the body is already submerged); the destination
     * one cell below must be both passable and liquid — descending into
     * air is a Drop, not a swim. The executor interprets {@code sneak}
     * in water as downward thrust (see BotBodyEntity); on land the same
     * flag is just crouch, so the pathing layer does not need to know
     * which medium it is in.
     */
    record SwimDown(CellPos source, CellPos destination) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return liquid(world, source) && passable(world, destination) && liquid(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 2.0;
        }
    }

    /**
     * Vertical ascent through a climbable cell (ladder, vine, weeping/
     * twisting vines). The source carries the climbable trait; the
     * destination is one above and must be body-passable (another
     * ladder cell or open air). No floor support required — the ladder
     * is the support, exactly as vanilla {@code Entity.onClimbable}
     * does not demand ground contact.
     *
     * <p>Executor: vanilla {@code LivingEntity.travel()} climbs
     * automatically when {@code onClimbable()} is true and horizontal
     * drive is present; {@code PathingBehavior} supplies {@code forward
     * >= ARRIVE_MIN_DRIVE} on every steer tick, so a pure-vertical
     * ladder run still gets the input vanilla needs. No special
     * jump branch is required — the climbable check in travel() runs
     * for every LivingEntity, including the bot's PathfinderMob carrier.
     */
    record ClimbUp(CellPos source, CellPos destination) implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return climbable(world, source)
                    && passable(world, destination)
                    && passable(world, new CellPos(destination.x(), destination.y() + 1, destination.z()));
        }

        @Override
        public double cost(WorldView world) {
            return 2.0;
        }
    }
}
