package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The task-verb wiring bundle: the seat verbs submit missions to, the
 * boundary-D event stream, and the game-day / time-of-day stamp pair
 * every disclosure stamps. One record instead of the four-arg
 * parameter train every task-verb handler constructor repeated (the
 * SurvivalInputs precedent - a wiring bundle per family, built once
 * at the composition root).
 *
 * <p>Contract: see boundaries.md decision 18 (task verbs ride the
 * CommandBus); the bundle changes nothing about the seams, only the
 * parameter shape.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
public record VerbWiring(TaskArbiter arbiter, EventQueue events, LongSupplier day, LongSupplier tod) {

    /** Rejects nulls once at construction; the wiring is built once per assembly. */
    public VerbWiring {
        Objects.requireNonNull(arbiter, "arbiter");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(tod, "tod");
    }
}
