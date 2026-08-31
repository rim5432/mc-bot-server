package com.mcbot.mcbotserver.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-lifetime epoch allocator for the event stream's
 * {@code resetAt} marker (issue 0015 resetAt epoch honesty): the
 * marker used to live in the queue instance, so every /botspawn and
 * every JVM boot minted a fresh marker at 1 - colliding with client
 * bookmarks whose stored epoch was also 1, silently stranding new
 * events below the stale cursor. This store owns one strictly
 * increasing sequence per server; each queue construction and each
 * /bot reset draws the next value, making {@code resetAt} truly
 * beyond-head across bot restarts and reboots.
 *
 * <p>Crash window: values persist on world save (setDirty here,
 * flushed by autosave and clean stops). A crash may rewind to the
 * last save; the client-side id-space backstop (cursor beyond stream
 * head) still detects that restart shape - the harness-interaction
 * restart rule keeps both signals.
 *
 * <p>Implementation note: adapter layer by necessity (SavedData is
 * engine storage); drawn through a plain {@code LongSupplier} seam,
 * so the queue in {@code core/} stays engine-free.
 */
public final class EventEpochStore extends SavedData {

    /** Storage key inside the overworld's data folder. */
    static final String DATA_NAME = "mcbotserver_event_epoch";

    private long lastGranted;

    private EventEpochStore() {}

    /**
     * Loads or creates the store bound to the overworld's data
     * storage - one sequence per server, regardless of the level a
     * bot spawns in.
     *
     * @param overworld the server's overworld; never null
     * @return the store; never null
     */
    public static EventEpochStore of(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(EventEpochStore::load, EventEpochStore::new, DATA_NAME);
    }

    private static EventEpochStore load(CompoundTag tag) {
        EventEpochStore store = new EventEpochStore();
        store.lastGranted = tag.getLong("lastGranted");
        return store;
    }

    /**
     * Grants the next epoch in the server-lifetime sequence.
     *
     * @return a value strictly greater than every prior grant; never
     *         negative
     */
    public long nextEpoch() {
        setDirty();
        return ++lastGranted;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("lastGranted", lastGranted);
        return tag;
    }
}
