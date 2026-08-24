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
        } else if (rot == null) {
            // Idle presence (issue 0005 P0.2/P0.3): no behavior owns
            // ROT this tick - between plans, after arrival, reflex
            // freeze. Turn the head toward the nearest player. Pure
            // presentation: it rides the same setTargetRotation write
            // path as claim-resolved looks and never touches a
            // mission channel. When pathing or combat claims ROT the
            // same tick, that claim wins and this stays silent.
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
        return winners;
    }

    @Override
    public void clearAllIntents() {
        delegate.clearAllIntents();
        body.setDrive(0f, 0f, false);
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
            return;
        }
        body.setTargetRotation(
            IdleLook.turnTowardYaw(body.getYRot(), target.yawDeg()),
            IdleLook.turnTowardPitch(body.getXRot(), target.pitchDeg()));
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
