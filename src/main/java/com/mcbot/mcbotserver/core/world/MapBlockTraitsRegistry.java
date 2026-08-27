package com.mcbot.mcbotserver.core.world;

import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.BlockTraitsRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link BlockTraitsRegistry}: a flat string-to-traits map,
 * mutable during build, immutable after {@link #seal()}. The default
 * offline registry used by tests; the datapack pipeline replaces the
 * entries at load time and seals before exposing the registry to the
 * planner.
 *
 * <p>Contract: see {@link BlockTraitsRegistry} for the lookup rules.
 * State-aware keys are matched exactly when present; the bare-id
 * fallback matches any state. The default (no match) is
 * {@link BlockTraits#defaults()}, never null.
 *
 * <p>Why mutable-then-seal rather than pure builder: the datapack
 * pipeline loads many JSON files and merges them in stages. Each
 * stage calls {@link #register}, then a final pass calls
 * {@link #seal} and the planner takes a read-only view. For tests
 * that don't care, {@code seal()} is a no-op (it does not prevent
 * further {@code register} calls) and the registry stays mutable for
 * the whole lifetime.
 */
public final class MapBlockTraitsRegistry implements BlockTraitsRegistry {

    private final Map<String, BlockTraits> entries = new HashMap<>();
    private boolean sealed;

    /** Builds an empty registry. */
    public MapBlockTraitsRegistry() {}

    /**
     * Builds a registry pre-populated from a map. The supplied map's
     * entries are copied; later mutations of the caller's map do not
     * leak through.
     *
     * @param initial entries to copy in; must not be null
     */
    public MapBlockTraitsRegistry(Map<String, BlockTraits> initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial must not be null");
        }
        entries.putAll(initial);
    }

    @Override
    public BlockTraits traitsFor(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
        // Exact match first: state-aware keys win.
        BlockTraits t = entries.get(blockId);
        if (t != null) {
            return t;
        }
        // Fallback: bare id without state suffix. Slab[bottom] ->
        // try "slab" if no exact entry. The base id is everything
        // before the first '['.
        int bracket = blockId.indexOf('[');
        if (bracket > 0) {
            t = entries.get(blockId.substring(0, bracket));
            if (t != null) {
                return t;
            }
        }
        return BlockTraits.defaults();
    }

    @Override
    public MapBlockTraitsRegistry register(String blockId, BlockTraits traits) {
        if (sealed) {
            throw new IllegalStateException("registry is sealed; build a new one");
        }
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
        if (traits == null) {
            throw new IllegalArgumentException("traits must not be null");
        }
        entries.put(blockId, traits);
        return this;
    }

    /**
     * Freeze the registry for runtime. After {@code seal} further
     * {@link #register} calls fail fast with
     * {@link IllegalStateException}; the planner sees an immutable
     * map and can read it without synchronization.
     *
     * @return this registry, sealed
     */
    public MapBlockTraitsRegistry seal() {
        this.sealed = true;
        return this;
    }

    /**
     * Read-only snapshot of the registered entries. Intended for
     * diagnostics and the JSON dump path; not a live view.
     *
     * @return unmodifiable view of the entries map
     */
    public Map<String, BlockTraits> entriesView() {
        return Collections.unmodifiableMap(entries);
    }
}
