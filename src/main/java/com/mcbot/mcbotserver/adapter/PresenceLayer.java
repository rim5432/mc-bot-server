package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.IdleLook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.InteractionHand;

/**
 * Cosmetic body expression for the entity binding (issue 0005):
 * idle head tracking toward the nearest player, the idle sweep when
 * nobody is near, and the walk-fidget swing bursts. Pure
 * presentation - it rides the body's local rotation and swing
 * writes, never a mission channel, and stays silent whenever a
 * behavior owns the channel in question.
 *
 * <p>Implementation note: server tick thread only, driven from
 * BindingActor.flush once per tick.
 */
final class PresenceLayer {

    private final BotBodyEntity body;
    // Walk-fidget state (issue 0005 P3): one seeded generator owns the
    // whole sequence so every run is identical per design rule 3.
    private final Random fidgetRandom = new Random(FIDGET_SEED);
    private int fidgetCountdown = nextFidgetInterval();
    private int fidgetEchoSwingDelay = -1;
    // Idle-sweep state (issue 0005 P2.3).
    private int idleTicks;
    private float idleBaseYaw;
    private boolean sweepRight;

    /**
     * @param body the body whose presentation this layer drives;
     *             never null
     */
    PresenceLayer(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * Idle head-track pass for a tick with no ROT claim: delegates
     * target choice and turn pacing to {@link IdleLook} and echoes
     * the result through the body's normal rotation write.
     * Spectators and removed players are skipped; distance and
     * selection semantics live in the policy so layer-1 tests pin
     * them.
     */
    // contract: see issues/0005-player-feel-motion-layer.md P0 (idle
    // look is adapter-local presentation, never an Intent)
    void tickIdleRot() {
        var eye = body.getEyePosition();
        List<Vec3> candidates = new ArrayList<>();
        for (var player : body.level().players()) {
            if (player.isRemoved() || player.isSpectator()) {
                continue;
            }
            var playerEye = player.getEyePosition();
            candidates.add(new Vec3(playerEye.x, playerEye.y, playerEye.z));
        }
        IdleLook.Target target = IdleLook.nearestTarget(new Vec3(eye.x, eye.y, eye.z), candidates);
        if (target == null) {
            // Nobody near: periodic sweep instead (issue 0005 P2.3).
            applyIdleSweep();
            return;
        }
        idleTicks = 0;
        body.setTargetRotation(
                IdleLook.turnTowardYaw(body.getYRot(), target.yawDeg()),
                IdleLook.turnTowardPitch(body.getXRot(), target.pitchDeg()));
    }

    /**
     * A behavior's ROT claim won this tick: reset the idle
     * accumulation so the next idle tick re-anchors its sweep yaw.
     */
    void onRotClaim() {
        idleTicks = 0;
    }

    /**
     * Idle sweep pass (issue 0005 P2.3): flip a glance direction every
     * {@link IdleLook#SWEEP_INTERVAL_TICKS} idle ticks, anchored to the
     * yaw at the moment idleness began. Deterministic by construction
     * - a counter flip, no randomness.
     */
    // contract: see issues/0005-player-feel-motion-layer.md P2 (idle
    // sweep is adapter-local presentation, never an Intent)
    private void applyIdleSweep() {
        if (idleTicks == 0) {
            idleBaseYaw = body.getYRot();
        }
        idleTicks++;
        if (idleTicks % IdleLook.SWEEP_INTERVAL_TICKS == 0) {
            sweepRight = !sweepRight;
        }
        float sweepYaw = idleBaseYaw + (sweepRight ? IdleLook.SWEEP_AMPLITUDE_DEG : -IdleLook.SWEEP_AMPLITUDE_DEG);
        body.setTargetRotation(
                IdleLook.turnTowardYaw(body.getYRot(), sweepYaw), IdleLook.turnTowardPitch(body.getXRot(), 0f));
    }

    /** Fixed seed for the fidget interval sequence (design rule 3). */
    public static final long FIDGET_SEED = 0x5EED0005L;

    /** Fewest walking ticks between fidget bursts (issue 0005 P3). */
    public static final int FIDGET_MIN_INTERVAL_TICKS = 40;

    /** Most walking ticks between fidget bursts (issue 0005 P3). */
    public static final int FIDGET_MAX_INTERVAL_TICKS = 120;

    /** One-in-N chance a burst plays a second swing after the first. */
    public static final int FIDGET_ECHO_CHANCE = 3;

    /** Ticks between the first and second swing of a burst - past the
     *  vanilla swing animation length so the arc plays twice. */
    public static final int FIDGET_ECHO_DELAY_TICKS = 8;

    /** Claim priority at which a holder counts as combat for fidget
     *  suppression (combat owns ROT/USE at 20; pathing sits at 10). */
    public static final int COMBAT_PRIORITY_BAND = 20;

    private int nextFidgetInterval() {
        return FIDGET_MIN_INTERVAL_TICKS
                + fidgetRandom.nextInt(FIDGET_MAX_INTERVAL_TICKS - FIDGET_MIN_INTERVAL_TICKS + 1);
    }

    /**
     * Walk-fidget pass (issue 0005 P3): occasional main-hand swing
     * bursts while walking, one every {@link FIDGET_MIN_INTERVAL_TICKS}
     * to {@link FIDGET_MAX_INTERVAL_TICKS} walking ticks. The countdown
     * only advances while a drive claim is actually flowing, so pauses
     * do not mint fidgets.
     *
     * <p>Semantic boundary (binding, from the issue): the swing MUST
     * bypass the USE channel - {@code Intent.Use} means "act with the
     * main hand" and deals melee damage; routing cosmetic swings
     * through it would convert idle flavor into combat input. Direct
     * {@code body.swing} only, and suppressed while combat owns the
     * arbiter (a USE claim or a combat-priority ROT claim this tick)
     * - never swing idly mid-fight.
     */
    // contract: see issues/0005-player-feel-motion-layer.md P3 (the
    // marker that prevents promotion into Intent.Use)
    void tickWalkFidget(Map<Channel, Claim> winners, Claim move, Claim rot) {
        boolean walking = move != null && move.intent() instanceof Intent.Move m && m.forward() >= 0.5;
        boolean combatOwnsArbiter =
                winners.get(Channel.USE) != null || (rot != null && rot.priority() >= COMBAT_PRIORITY_BAND);
        if (!walking || combatOwnsArbiter) {
            return;
        }
        if (fidgetEchoSwingDelay > 0 && --fidgetEchoSwingDelay == 0) {
            body.swing(InteractionHand.MAIN_HAND);
        }
        if (--fidgetCountdown <= 0) {
            body.swing(InteractionHand.MAIN_HAND);
            if (fidgetRandom.nextInt(FIDGET_ECHO_CHANCE) == 0) {
                fidgetEchoSwingDelay = FIDGET_ECHO_DELAY_TICKS;
            }
            fidgetCountdown = nextFidgetInterval();
        }
    }
}
