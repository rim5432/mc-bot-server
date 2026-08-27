package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;

/**
 * Shared rigs for the tick-pipeline gate family: one canonical
 * {@link BotController} wiring (fixed GameClock day=1 / tod=6000;
 * health arrives through the caller's float array so tests bleed and
 * heal it mid-drive) plus the walkable strip mover-driven scenarios
 * need to plan routes.
 *
 * <p>Shared test infrastructure only; not part of any boundary.
 */
final class TickGateFixtures {

    private TickGateFixtures() {}

    /**
     * Canonical controller wiring for the pipeline gates: survival
     * feed reads the caller-owned health array every tick, so tests
     * mutate {@code health[0]} to drive preemption and recovery.
     *
     * @param health   mutable health cell the reflex feed polls; must
     *                 not be null
     * @param layer    reflex layer under test; must not be null
     * @param arbiter  mission arbiter; must not be null
     * @param behavior the single always-on behavior; must not be null
     * @param actor    recording actor; must not be null
     * @param events   event queue assertions drain; must not be null
     * @return the wired controller, never null
     */
    static BotController controller(
            float[] health,
            SurvivalReflexLayer layer,
            TaskArbiter arbiter,
            Behavior behavior,
            Actor actor,
            EventQueue events) {
        return new BotController(
                layer,
                arbiter,
                List.of(behavior),
                actor,
                () -> new CellPos(0, 64, 0),
                () -> health[0],
                new BotController.GameClock() {
                    @Override
                    public long day() {
                        return 1L;
                    }

                    @Override
                    public long timeOfDayTicks() {
                        return 6000L;
                    }
                },
                events,
                ctx -> {});
    }

    /**
     * Walkable strip so planner-based mover tests can find routes.
     *
     * @return smooth-stone floor spanning x 0..60, z -2..12; never
     *         null
     */
    static MockWorldView flooredWorld() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 60; x++) {
            for (int z = -2; z <= 12; z++) {
                world.putBlock(new BlockSnapshot(new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }
}
