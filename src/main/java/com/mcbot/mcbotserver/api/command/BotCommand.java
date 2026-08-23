package com.mcbot.mcbotserver.api.command;

import java.util.Map;

/**
 * Boundary-D command payload: an opaque verb plus its raw argument map.
 *
 * <p>Contract: see boundaries.md Boundary D protocol. The bot does not
 * interpret commands semantically beyond verb dispatch and structural
 * validation — the vocabulary may grow without touching the seam.
 * Concrete typed commands (e.g. GotoCommand) are built on top of this
 * shape by Stage 1 work.
 *
 * @param verb   dispatch key registered with the command router;
 *               lowercase, never null or blank
 * @param args   raw string arguments keyed by name; never null,
 *               possibly empty
 */
public record BotCommand(String verb, Map<String, String> args) {

    /**
     * Creates a validated command envelope.
     *
     * @param verb dispatch key; must not be null or blank
     * @param args argument map; must not be null
     */
    public BotCommand {
        if (verb == null || verb.isBlank()) {
            throw new IllegalArgumentException("verb must not be blank");
        }
        args = Map.copyOf(args);
    }
}
