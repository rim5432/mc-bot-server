package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParseException;
import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.core.command.GotoCommandJson;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Stage-1 JSON wire form: numeric args survive the string round-trip
 * and the codec stays vocabulary-blind.
 *
 * <p>Contract: see boundaries.md decision 18.
 */
class GotoCommandJsonTest {

    /** Numeric wire values decode as strings the validator can parse. */
    @Test
    void numericArgsRoundTrip() {
        String wire = "{\"verb\":\"goto\",\"args\":" + "{\"x\":10,\"y\":64,\"z\":-3,\"tolerance\":1}}";
        BotCommand decoded = GotoCommandJson.fromJson(wire);
        assertEquals("goto", decoded.verb());
        assertEquals(Map.of("x", "10", "y", "64", "z", "-3", "tolerance", "1"), decoded.args());

        String encoded = GotoCommandJson.toJson(decoded);
        BotCommand again = GotoCommandJson.fromJson(encoded);
        assertEquals(decoded, again, "wire form must be stable");
    }

    /** Empty args is a legal envelope. */
    @Test
    void emptyArgsRoundTrip() {
        BotCommand cmd = new BotCommand("goto", Map.of());
        assertEquals(cmd, GotoCommandJson.fromJson(GotoCommandJson.toJson(cmd)));
    }

    /** Missing verb violates the required-shape contract and must throw JsonParseException, not NPE. */
    @Test
    void missingVerbThrowsJsonParseException() {
        assertThrows(JsonParseException.class, () -> GotoCommandJson.fromJson("{\"args\":{\"x\":\"1\"}}"));
    }
}
