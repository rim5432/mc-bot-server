package com.mcbot.mcbotserver.api.inventory;

/**
 * Canonical item-id normalization at the wire boundary.
 *
 * <p>Snapshots serialize item ids as registry keys ({@code minecraft:iron_ore}),
 * but wire consumers historically pass bare ids ({@code iron_ore}) to deposit
 * and recipe-lookup verbs. The planner matches on {@link String#equals}, so a
 * bare id never matches a namespaced id and the supply count reads as zero —
 * producing the misleading {@code "not enough: have 0, need N"} error even
 * when the player region holds the item.
 *
 * <p>This class is the single normalization point: every bot-side wire entry
 * that receives an item id calls {@link #normalize(String)} before handing the
 * value to core logic. It lives in {@code api/} (zero MC imports) so a non-JVM
 * harness could bind to the same contract.
 *
 * <p>Normalization is idempotent: an already-canonical id passes through
 * unchanged, so callers that already qualify ids (e.g. {@code minecraft:cobblestone})
 * pay nothing.
 *
 * @see ItemView#itemId() the canonical format this class produces
 */
public final class ItemIds {

    /** The namespace applied to bare ids. Vanilla items live here. */
    public static final String DEFAULT_NAMESPACE = "minecraft";

    private ItemIds() {}

    /**
     * Normalizes a raw item id into the canonical {@code namespace:path} form.
     *
     * <p>Rules:
     * <ul>
     *   <li>null or blank -> {@link IllegalArgumentException} with a precise message</li>
     *   <li>contains {@code ':'} -> returned as-is (assumed already canonical;
     *       modded namespaces such as {@code create:iron_ore} pass through)</li>
     *   <li>otherwise -> {@code "minecraft:" + raw}</li>
     * </ul>
     *
     * <p>This method does NOT fold case — Minecraft registry keys are
     * case-sensitive and callers are responsible for correct casing.
     *
     * @param raw the wire-level item id; never null or blank
     * @return the canonical namespaced id; never null or blank
     * @throws IllegalArgumentException when raw is null or blank
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("item id must not be null or blank");
        }
        String trimmed = raw.trim();
        if (trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return DEFAULT_NAMESPACE + ':' + trimmed;
    }

    /**
     * Whether a raw id is already in canonical namespaced form.
     *
     * @param raw the wire-level item id; may be null
     * @return true when raw is non-blank and contains a namespace separator
     */
    public static boolean hasNamespace(String raw) {
        return raw != null && !raw.isBlank() && raw.indexOf(':') >= 0;
    }
}
