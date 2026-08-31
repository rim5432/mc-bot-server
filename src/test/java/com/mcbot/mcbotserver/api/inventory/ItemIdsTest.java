package com.mcbot.mcbotserver.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Offline gates for {@link ItemIds} wire-boundary normalization. Pure
 * Java — zero MC imports, runs on any JUnit 5 runner.
 *
 * <p>Contract: item ids on the wire are canonical registry keys
 * ({@code minecraft:iron_ore}); bare ids ({@code iron_ore}) must be
 * normalized at every bot-side entry point before core logic sees them.
 * Normalization is idempotent and never folds case.
 */
class ItemIdsTest {

    @Test
    void normalizeBareIdAddsDefaultNamespace() {
        assertEquals("minecraft:iron_ore", ItemIds.normalize("iron_ore"));
    }

    @Test
    void normalizeAlreadyNamespacedPassesThroughUnchanged() {
        assertEquals("minecraft:iron_ore", ItemIds.normalize("minecraft:iron_ore"));
    }

    @Test
    void normalizeModdedNamespacePassesThroughUnchanged() {
        assertEquals("create:iron_ore", ItemIds.normalize("create:iron_ore"));
    }

    @Test
    void normalizeTrimsSurroundingWhitespace() {
        assertEquals("minecraft:iron_ore", ItemIds.normalize("  iron_ore  "));
    }

    @Test
    void normalizeTrimsWhitespaceOnNamespacedId() {
        assertEquals("minecraft:iron_ore", ItemIds.normalize("  minecraft:iron_ore  "));
    }

    @Test
    void normalizeNullThrowsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ItemIds.normalize(null));
        assertTrue(ex.getMessage().contains("null"), "message should mention null: " + ex.getMessage());
    }

    @Test
    void normalizeEmptyStringThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ItemIds.normalize(""));
    }

    @Test
    void normalizeWhitespaceOnlyThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ItemIds.normalize("   "));
    }

    @Test
    void normalizeIsIdempotent() {
        String once = ItemIds.normalize("iron_ore");
        String twice = ItemIds.normalize(once);
        assertEquals(once, twice);
        assertEquals("minecraft:iron_ore", twice);
    }

    @Test
    void hasNamespaceTrueForDefaultNamespace() {
        assertTrue(ItemIds.hasNamespace("minecraft:iron_ore"));
    }

    @Test
    void hasNamespaceTrueForModdedNamespace() {
        assertTrue(ItemIds.hasNamespace("create:iron_ore"));
    }

    @Test
    void hasNamespaceFalseForBareId() {
        assertFalse(ItemIds.hasNamespace("iron_ore"));
    }

    @Test
    void hasNamespaceFalseForNull() {
        assertFalse(ItemIds.hasNamespace(null));
    }

    @Test
    void hasNamespaceFalseForBlank() {
        assertFalse(ItemIds.hasNamespace(""));
        assertFalse(ItemIds.hasNamespace("   "));
    }
}
