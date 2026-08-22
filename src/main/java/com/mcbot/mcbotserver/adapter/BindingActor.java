package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;

import net.minecraft.world.InteractionHand;

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

    private final com.mcbot.mcbotserver.core.actor.ChannelArbiter delegate =
        new com.mcbot.mcbotserver.core.actor.ChannelArbiter();
    private final BotBodyEntity body;
    private boolean lastUsePressing;

    /**
     * Creates an actor bound to one body.
     *
     * @param body the physical carrier; never null
     */
    public BindingActor(BotBodyEntity body) {
        this.body = Objects.requireNonNull(body, "body");
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
        } else {
            body.setDrive(0f, 0f, false);
        }

        Claim rot = winners.get(Channel.ROT);
        if (rot != null && rot.intent() instanceof Intent.Look l) {
            body.setTargetRotation(l.yawDeg(), l.pitchDeg());
        }

        Claim use = winners.get(Channel.USE);
        if (use != null && use.intent() instanceof Intent.Use u) {
            if (u.pressing() && !lastUsePressing) {
                resolveMelee();
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
        return winners;
    }

    @Override
    public void clearAllIntents() {
        delegate.clearAllIntents();
        body.setDrive(0f, 0f, false);
    }

    /**
     * Half-angle of the melee aim cone, in degrees. A swing hits the
     * nearest living hostile whose direction from the eyes stays
     * inside this cone - deliberately a cone test rather than vanilla
     * ray-clip, because target data crosses the boundary at block
     * granularity and a ray would whiff on sub-cell offsets.
     */
    public static final double AIM_CONE_DEG = 45.0;

    /** Damage per landed skeleton swing; zombie-scale for now. */
    public static final float MELEE_DAMAGE = 3f;

    /** Swing reach beyond the behavior-side ATTACK_REACH guard. */
    private static final double REACH = 3.5;

    /**
     * Resolve one USE press as "act with main hand": always swing;
     * additionally hurt the nearest living hostile inside the reach
     * box and the aim cone (numen-notes fidelity note: native
     * click-targeting is approximated by cone resolution because
     * EntitySnapshot granularity is block-level).
     *
     * <p>Runs during flush on the server tick thread.
     */
    // contract: see boundaries.md decision 14 (USE = act with main hand)
    private void resolveMelee() {
        body.swing(InteractionHand.MAIN_HAND);
        var view = body.getViewVector(1.0F).normalize();
        var eye = body.getEyePosition();
        var box = body.getBoundingBox().inflate(REACH);
        net.minecraft.world.entity.LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.LivingEntity e : body.level()
                .getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    box, other -> other != body && other.isAlive())) {
            var key = net.minecraftforge.registries.ForgeRegistries
                .ENTITY_TYPES.getKey(e.getType());
            if (key == null || !LevelThreatSensor.hostileTypes()
                    .contains(key.toString())) {
                continue;
            }
            var toTarget = e.position().add(0, e.getBbHeight() / 2, 0)
                .subtract(eye);
            double dist = toTarget.length();
            if (dist > REACH) {
                continue;
            }
            if (view.dot(toTarget.normalize())
                < Math.cos(Math.toRadians(AIM_CONE_DEG))) {
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        if (best != null) {
            com.mojang.logging.LogUtils.getLogger().info(
                "[melee] HIT dist={} hp={}", bestDist,
                best.getHealth());
            best.hurt(body.damageSources()
                .mobAttack(body), MELEE_DAMAGE);
        }
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
