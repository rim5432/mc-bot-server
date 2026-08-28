package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.pathing.Movement;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.MoveGraph;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration gates for the unreachable-goal escalation wiring: the
 * existing fast-fail (drained search -> empty cursor -> NO_PATH the
 * same tick) survives the NoPathEscalator refactor, a reachable goal
 * keeps a zero witness ledger, and the ledger is observable through
 * the keepalive snapshot.
 *
 * <p>Contract: see NoPathEscalator - the witness-counting logic is
 * unit-gated same-package in NoPathEscalatorTest; these gates pin the
 * PathingBehavior plumbing only. The field failure this guards (a
 * budget-cut partial loop burning the whole mission timeout) emerges
 * from async wall-clock conditions and is verified live, not here.
 */
class NoPathEscalationGateTest {

    /**
     * One viable edge between two cells; viability never consults
     * the world - the stub graph decides reachability by shape.
     */
    private record StubMove(CellPos from, CellPos to) implements Movement {

        @Override
        public CellPos source() {
            return from;
        }

        @Override
        public CellPos destination() {
            return to;
        }

        @Override
        public boolean isViable(WorldView world) {
            return true;
        }

        @Override
        public double cost(WorldView world) {
            return 1.0;
        }

        @Override
        public String describe() {
            return "stub " + from + "->" + to;
        }
    }

    /** Finite line graph along +x: walkable x in [0, extent]. */
    private static MoveGraph lineGraph(int extent) {
        return cell -> {
            int x = cell.x();
            if (x < extent && cell.y() == 64 && cell.z() == 0) {
                return List.of(new StubMove(cell, new CellPos(x + 1, 64, 0)));
            }
            return List.of();
        };
    }

    /**
     * A goal outside the finite graph drains the open set on the
     * first search: empty waypoints, empty cursor, NO_PATH the same
     * tick - the pre-escalator fast fail must survive the refactor.
     */
    @Test
    void drainedUnreachableGoalFailsNoPathSameTick() {
        Vec3[] pose = {new Vec3(0.5, 64.0, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover", () -> pose[0], lineGraph(5));
        WorldView world = new MockWorldView();
        GoalBlock goal = new GoalBlock(new CellPos(10, 64, 0));

        ExecutionReport report = mover.tick(world, Directive.of(goal), new RecordingActor());

        assertEquals(ExecutionReport.Status.FAILED, report.status());
        assertEquals("NO_PATH", report.reason());
    }

    /**
     * A reachable goal completes with the witness ledger at zero
     * throughout - improving adoptions never mint evidence.
     */
    @Test
    void reachableGoalKeepsZeroWitnessLedger() {
        Vec3[] pose = {new Vec3(0.5, 64.0, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover", () -> pose[0], lineGraph(10));
        WorldView world = new MockWorldView();
        CellPos goalCell = new CellPos(10, 64, 0);
        GoalBlock goal = new GoalBlock(goalCell);
        var actor = new RecordingActor();

        ExecutionReport report = ExecutionReport.running();
        for (int tick = 0; tick < 60 && report.status() != ExecutionReport.Status.SUCCESS; tick++) {
            report = mover.tick(world, Directive.of(goal), actor);
            // Rig walks one cell per tick toward the steer target.
            if (!PathingTestAccess.planEmpty(mover)) {
                CellPos wp = PathingTestAccess.steerTarget(mover);
                pose[0] = new Vec3(wp.x() + 0.5, wp.y(), wp.z() + 0.5);
            }
            assertEquals("0", mover.keepaliveAttrs(pose[0], goalCell).get("noPathWitnesses"));
        }
        assertEquals(ExecutionReport.Status.SUCCESS, report.status());
    }
}
