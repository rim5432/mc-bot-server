package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.IdleLook;

import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

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
            idleTicks = 0;
        } else if (rot == null) {
            // Idle presence (issue 0005 P0.2/P0.3): no behavior owns
            // ROT this tick - between plans, after arrival, reflex
            // freeze. Turn the head toward the nearest player, or
            // sweep when nobody is near (P2.3). Pure presentation: it
            // rides the same setTargetRotation write path as
            // claim-resolved looks and never touches a mission
            // channel. When pathing or combat claims ROT the same
            // tick, that claim wins and this stays silent.
            applyIdleLook();
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

        tickWalkFidget(winners, move, rot);
        return winners;
    }

    @Override
    public void clearAllIntents() {
        delegate.clearAllIntents();
        body.setDrive(0f, 0f, false);
        body.setSprinting(false);
    }

    /**
     * Idle head-track pass: delegates target choice and turn pacing
     * to {@link IdleLook} and echoes the result through the body's
     * normal rotation write. Spectators and removed players are
     * skipped; distance and selection semantics live in the policy
     * so layer-1 tests pin them.
     */
    // contract: see issues/0005-player-feel-motion-layer.md P0 (idle
    // look is adapter-local presentation, never an Intent)
    private void applyIdleLook() {
        var eye = body.getEyePosition();
        List<Vec3> candidates = new ArrayList<>();
        for (var player : body.level().players()) {
            if (player.isRemoved() || player.isSpectator()) {
                continue;
            }
            var playerEye = player.getEyePosition();
            candidates.add(new Vec3(playerEye.x, playerEye.y,
                playerEye.z));
        }
        IdleLook.Target target =
            IdleLook.nearestTarget(new Vec3(eye.x, eye.y, eye.z),
                candidates);
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
        float sweepYaw = idleBaseYaw + (sweepRight
            ? IdleLook.SWEEP_AMPLITUDE_DEG
            : -IdleLook.SWEEP_AMPLITUDE_DEG);
        body.setTargetRotation(
            IdleLook.turnTowardYaw(body.getYRot(), sweepYaw),
            IdleLook.turnTowardPitch(body.getXRot(), 0f));
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
        var dir = new net.minecraft.world.phys.Vec3(view.x, 0, view.z)
            .normalize();
        var eye = body.getEyePosition();
        var clip = body.level().clip(new net.minecraft.world.level
            .ClipContext(eye,
                eye.add(dir.scale(SPRINT_CLEARANCE_BLOCKS)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                body));
        return clip.getType()
            == net.minecraft.world.phys.HitResult.Type.MISS;
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
        return FIDGET_MIN_INTERVAL_TICKS + fidgetRandom.nextInt(
            FIDGET_MAX_INTERVAL_TICKS - FIDGET_MIN_INTERVAL_TICKS + 1);
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
    private void tickWalkFidget(Map<Channel, Claim> winners, Claim move,
                                Claim rot) {
        boolean walking = move != null
            && move.intent() instanceof Intent.Move m
            && m.forward() >= 0.5;
        boolean combatOwnsArbiter = winners.get(Channel.USE) != null
            || (rot != null && rot.priority() >= COMBAT_PRIORITY_BAND);
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

    /**
     * Swing reach measured EYE TO TARGET BOUNDING-BOX SURFACE,
     * aligned with vanilla's player metric (~3.0) so the bot holds no
     * hidden long-arm advantage. The old center-to-center 3.5 read
     * ~0.4 blocks farther than vanilla on horizontal targets.
     */
    public static final double MELEE_REACH_SURFACE = 3.0;

    /**
     * Loose prefilter for the candidate net: bounding-box inflation
     * before the exact surface-distance gate. Generous on purpose -
     * the gate below is the authority.
     */
    private static final double CANDIDATE_NET = MELEE_REACH_SURFACE
        + 1.5;

    /**
     * Slack for the line-of-sight clip: a hit point this close to the
     * target counts as grazing the hitbox face, not as occlusion.
     */
    public static final double MELEE_CLIP_SLACK = 0.35;

    /**
     * Whether terrain blocks the swing line. A melee reach number
     * alone lies around corners: distance and cone can be satisfied
     * while a wall eats the line; the clip asks the real voxel grid.
     *
     * @param eye          the swing origin; never null
     * @param targetCenter candidate mid-height position; never null
     * @return true when solid terrain blocks the line before the
     *         target (grazes at the hitbox face stay clear)
     */
    private boolean sightBlocked(net.minecraft.world.phys.Vec3 eye,
                                 net.minecraft.world.phys.Vec3
                                     targetCenter) {
        var clip = body.level().clip(new net.minecraft.world.level
            .ClipContext(eye, targetCenter,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                body));
        if (clip.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return false;
        }
        return eye.distanceTo(clip.getLocation())
            < eye.distanceTo(targetCenter) - MELEE_CLIP_SLACK;
    }

    /**
     * Whether the swing line passes through lava. Water stays
     * transparent by v1 semantics; lava is opaque because melee
     * across it is impossible and pretending otherwise turns the bot
     * into a sandbag swinging at the far shore.
     *
     * @param eye          the swing origin; never null
     * @param targetCenter candidate mid-height position; never null
     * @return true when any sampled point along the line sits in lava
     */
    private boolean lavaBetween(net.minecraft.world.phys.Vec3 eye,
                                net.minecraft.world.phys.Vec3
                                    targetCenter) {
        var delta = targetCenter.subtract(eye);
        int steps = (int) Math.ceil(delta.length() / 0.5);
        for (int i = 1; i < steps; i++) {
            var at = eye.add(delta.scale((double) i / steps));
            if (body.level().getBlockState(
                    net.minecraft.core.BlockPos.containing(at))
                    .is(net.minecraft.world.level.block.Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

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
        var box = body.getBoundingBox().inflate(CANDIDATE_NET);
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
            // Vanilla-aligned reach: squared distance from the eye to
            // the CLOSEST SURFACE of the target's bounding box - never
            // center-to-center, which read ~0.4 blocks longer than a
            // player could legitimately swing.
            double surfDistSq = e.getBoundingBox().distanceToSqr(eye);
            if (surfDistSq > MELEE_REACH_SURFACE * MELEE_REACH_SURFACE) {
                continue;
            }            var targetCenter = e.position()
                .add(0, e.getBbHeight() / 2, 0);
            var toTarget = targetCenter.subtract(eye);
            if (view.dot(toTarget.normalize())
                < Math.cos(Math.toRadians(AIM_CONE_DEG))) {
                continue;
            }
            if (sightBlocked(eye, targetCenter)
                || lavaBetween(eye, targetCenter)) {
                continue;
            }
            if (surfDistSq < bestDist) {
                bestDist = surfDistSq;
                best = e;
            }
        }
        if (best != null) {
            // Kept at DEBUG: useful for in-engine melee tracing when
            // tuning ATTACK_REACH / AIM_CONE_DEG, not noise the
            // production log needs at INFO. The 8c09134 standoff
            // bug was found by walking these lines; a future tuning
            // pass will reach for the same lever.
            com.mojang.logging.LogUtils.getLogger().debug(
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
