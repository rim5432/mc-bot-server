package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Forceload-backed chunk ticket that follows the body (issue 0015
 * section S, the entity-ticking ticket gap): on a bare dedicated
 * server nothing grants the bot's chunks an entity-ticking ticket,
 * so once the chunk unloads the body's physics and AI freeze while
 * the pipeline - server-tick driven - keeps failing tasks honestly.
 * Pinned live by the boundary-D QA round (receipt
 * qa-results/boundary-d/receipt-20260831-051354.json, case C1/C1b).
 *
 * <p>Mechanism choice: {@code ServerLevel.setChunkForced} rather
 * than a custom region ticket. Both grant level 31
 * ({@code ChunkLevel.ENTITY_TICKING_LEVEL}) in theory, but only the
 * forceload path was proven to load and entity-tick the chunk live
 * on 2026-08-31 - a {@code TicketType.create} region ticket at
 * distance 3 logged as granted yet never loaded its chunk (probe:
 * setblock into the chunk kept answering "not loaded"). Forceload
 * is also operator-visible via {@code forceload query}, which is
 * exactly how this gap was first mitigated by hand.
 *
 * <p>Trade-offs, both accepted: a border crossing skips one entity
 * tick (the neighbor sits at block-ticking level 32 until the
 * follow-grant moves the forceload - momentum persists, so a walk
 * continues); and forceload persists across restarts, so a crashed
 * server can leak the body's chunk - the runbook recovery is
 * {@code forceload remove all}, and the release paths below cover
 * every orderly shutdown (despawn, replace, death).
 *
 * <p>Lifecycle: {@link #tick(Entity)} follows chunk and level
 * changes, grants at the body's chunk while it sits unloaded (the
 * chunk then loads and the body re-materializes - the resurrection
 * the C1 forceload probe proved), and self-releases only on
 * destructive removal (killed, discarded); /botdespawn and the
 * /botspawn replace path call {@link #release()} directly because
 * their sessions stop ticking immediately, which would starve the
 * self-release.
 *
 * <p>Implementation note: server tick thread only, driven from
 * {@code BotAssembly.tickOnce} before the pipeline reads the world.
 */
public final class BotChunkTicket {

    private ServerLevel level;
    private ChunkPos current;
    private boolean released;

    /**
     * @param level the body's server level at assembly; never null
     */
    public BotChunkTicket(ServerLevel level) {
        this.level = level;
    }

    /**
     * Follows the body: claims the forceload at the body's current
     * chunk and moves it on chunk or level changes. Removal reasons
     * split two ways: a destroyed body (killed, discarded) releases,
     * while an UNLOADED body - parked by a teleport or a racing
     * unload - is the ticket's whole reason to exist: granting at
     * its chunk makes the chunk load and the body re-materialize.
     *
     * @param body the ticketed body; never null
     */
    public void tick(Entity body) {
        if (released) {
            return;
        }
        if (body.isRemoved()) {
            var reason = body.getRemovalReason();
            if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
                release();
                return;
            }
            // UNLOADED_TO_CHUNK / UNLOADED_WITH_PLAYER: keep granting
            // at the parked position until the chunk loads and the
            // body resumes; then the chunk-change branch takes over.
            grantAt(body.chunkPosition());
            return;
        }
        if (body.level() != level) {
            releaseCurrent();
            level = (ServerLevel) body.level();
            current = null;
        }
        grantAt(body.chunkPosition());
    }

    /**
     * Moves the grant when the tracked chunk differs; a no-op on
     * every tick the body stays put.
     *
     * @param now the chunk to hold loaded; never null
     */
    private void grantAt(ChunkPos now) {
        if (now.equals(current)) {
            return;
        }
        level.setChunkForced(now.x, now.z, true);
        if (current != null) {
            level.setChunkForced(current.x, current.z, false);
        }
        current = now;
    }

    /** Drops the current grant, if any. */
    private void releaseCurrent() {
        if (current != null) {
            level.setChunkForced(current.x, current.z, false);
            current = null;
        }
    }

    /** Drops the forceload exactly once; safe to call repeatedly. */
    public void release() {
        releaseCurrent();
        released = true;
    }
}
