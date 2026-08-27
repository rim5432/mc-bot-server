package com.mcbot.mcbotserver.core.command;

/**
 * Argument guards shared by every verb handler constructor: one
 * blanket null contract instead of per-handler boilerplate.
 */
final class CommandHandlerGuards {

    private CommandHandlerGuards() {}

    /** Throws when any collaborator is null - the handlers cannot run half-wired. */
    static void requireNonNullArgs(Object... collaborators) {
        for (Object arg : collaborators) {
            if (arg == null) {
                throw new IllegalArgumentException("arguments must not be null");
            }
        }
    }

    /** Throws when the cancel-routing bus is missing. */
    static void requireBus(CommandBus bus) {
        if (bus == null) {
            throw new IllegalArgumentException("bus must not be null");
        }
    }
}
