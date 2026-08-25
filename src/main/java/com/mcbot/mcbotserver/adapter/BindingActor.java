package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;

/**
 * Write half of the entity binding: resolves per-tick claims and
 * echoes the winners into the body's input fields. Vanilla physics
 * does the rest.
 *
 * <p>Contract: see ADR-0004 D2 and boundaries.md section A. An unwon
 * MOVE channel means halt — a body with no client must be told to
 * stand still every tick (numen-notes.md section 4). USE swings on the
 * rising press edge; SLOT writes the hotbar selection directly.
 *
 * <p>Implementation note: server tick thread only; flush is called
 * exactly once per tick by the pipeline's stage 4.
 */
// contract: see ADR-0004 D2 (four channels, per-tick expiring claims)
public final class BindingActor implements Actor {

    private final ChannelArbiter delegate = new ChannelArbiter();
    private final BotBodyEntity body;
    /** USE-press melee resolution (cone, reach, LOS, lava). */
    private final MeleeResolver melee;
    /** Cosmetic expression: idle look/sweep + walk fidget (0005). */
    private final PresenceLayer presence;
    /** Held-dig execution: destroy progress + the break (0009). */
    private final DigExecutor dig;
    private boolean lastUsePressing;

    /**
     * Creates an actor bound to one body.
     *
     * @param body the physical carrier; never null
     */
    public BindingActor(BotBodyEntity body) {
        this.body = Objects.requireNonNull(body, "body");
        this.melee = new MeleeResolver(body);
        this.presence = new PresenceLayer(body);
        this.dig = new DigExecutor(body);
    }

    @Override
    public void submit(Claim claim) {
        delegate.submit(claim);
    }

    @Override
    public Map<Channel, Claim> flush() {
        Map<Channel, Claim> winners = delegate.flush();

        Claim move = winners.get(Channel.MOVE);
        if (move != null && move.intent() instanceof Intent.Move m) {
            body.setDrive((float) clamp(m.forward()),
                (float) clamp(m.strafe()), m.jump());
            // Sprint gait (issue 0005 P1.1, issue 0004 F6(3) ruling
            // D4): adapter-local policy, no Intent field. Forward
            // stride plus straight clearance ahead. Suppress in fluid
            // - setSprinting in water is the swim boost and would
            // silently change crossing speed (F6(1) surface-lane
            // semantics).
            body.setSprinting(sprintClearAhead(m));
        } else {
            body.setDrive(0f, 0f, false);
            body.setSprinting(false);
        }

        Claim rot = winners.get(Channel.ROT);
        if (rot != null && rot.intent() instanceof Intent.Look l) {
            body.setTargetRotation(l.yawDeg(), l.pitchDeg());
            presence.onRotClaim();
        } else if (rot == null) {
            // Idle presence (issue 0005 P0.2/P0.3): no behavior owns
            // ROT this tick - between plans, after arrival, reflex
            // freeze. Turn the head toward the nearest player, or
            // sweep when nobody is near (P2.3). Pure presentation: it
            // rides the same setTargetRotation write path as
            // claim-resolved looks and never touches a mission
            // channel. When pathing or combat claims ROT the same
            // tick, that claim wins and this stays silent.
            presence.tickIdleRot();
        }

        Claim use = winners.get(Channel.USE);
        if (use != null && use.intent() instanceof Intent.Use u) {
            if (u.pressing() && !lastUsePressing) {
                melee.onUsePress();
            }
            lastUsePressing = u.pressing();
        } else {
            lastUsePressing = false;
        }

        Claim slot = winners.get(Channel.SLOT);
        if (slot != null
                && slot.intent() instanceof Intent.SelectSlot s) {
            body.selectedSlot = s.slot();
        }

        Claim interact = winners.get(Channel.INTERACT);
        if (interact != null
                && interact.intent() instanceof Intent.Dig d) {
            dig.dig(d.target());
        } else {
            // A dig no longer held must clear its crack broadcast the
            // same tick; vanilla's stop path does, and a stale crack
            // would lie to every observer.
            dig.release();
        }

        presence.tickWalkFidget(winners, move, rot);
        return winners;
    }

    @Override
    public void clearAllIntents() {
        delegate.clearAllIntents();
        body.setDrive(0f, 0f, false);
        body.setSprinting(false);
        dig.release();
    }

    /**
     * Straight clearance required ahead before the sprint gait
     * engages, in blocks (issue 0005 P1.1). Issue 0004 F6(3): a mob
     * carrier pays no hunger for sprinting.
     */
    public static final double SPRINT_CLEARANCE_BLOCKS = 5.0;

    /**
     * Drive strength at or above which the body counts as striding
     * for the sprint policy.
     */
    public static final double SPRINT_FORWARD_MIN = 0.95;

    /**
     * Whether the sprint gait may engage this tick: full forward
     * stride, not in fluid, and no wall inside
     * {@link #SPRINT_CLEARANCE_BLOCKS} along the facing. The clip
     * rides at eye height, so single steps ahead do not read as
     * walls - sprinting into a step is the sprint-jump gait, not a
     * collision.
     */
    // contract: see issues/0005-player-feel-motion-layer.md P1 +
    // issue 0004 D4 (sprint is an adapter policy, no Intent field)
    private boolean sprintClearAhead(Intent.Move m) {
        if (m.forward() < SPRINT_FORWARD_MIN
            || body.isInWater() || body.isInLava()) {
            return false;
        }
        var view = body.getViewVector(1.0F);
        var dir = new Vec3(view.x, 0, view.z).normalize();
        var eye = body.getEyePosition();
        var clip = body.level().clip(new ClipContext(eye,
            eye.add(dir.scale(SPRINT_CLEARANCE_BLOCKS)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return clip.getType() == HitResult.Type.MISS;
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
