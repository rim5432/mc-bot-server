package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * USE-press melee resolution for the entity binding: one rising
 * press edge means "act with the main hand" - always swing, plus
 * hurt the nearest living hostile inside the reach box and the aim
 * cone (numen-notes fidelity note: native click-targeting is
 * approximated by cone resolution because EntitySnapshot
 * granularity is block-level).
 *
 * <p>Implementation note: runs during flush on the server tick
 * thread only, like the rest of the binding.
 */
// contract: see boundaries.md decision 14 (USE = act with main hand)
final class MeleeResolver {

    /**
     * Half-angle of the melee aim cone, in degrees. A swing hits the
     * nearest living hostile whose direction from the eyes stays
     * inside this cone - deliberately a cone test rather than vanilla
     * ray-clip, because target data crosses the boundary at block
     * granularity and a ray would whiff on sub-cell offsets.
     */
    public static final double AIM_CONE_DEG = 45.0;

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
    private static final double CANDIDATE_NET = MELEE_REACH_SURFACE + 1.5;

    /**
     * Slack for the line-of-sight clip: a hit point this close to the
     * target counts as grazing the hitbox face, not as occlusion.
     */
    public static final double MELEE_CLIP_SLACK = 0.35;

    private final BotBodyEntity body;

    /**
     * @param body the swinging body; never null
     */
    MeleeResolver(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * Resolve one USE press: always swing; additionally hurt the
     * nearest qualifying hostile, if any.
     */
    void onUsePress() {
        body.swing(InteractionHand.MAIN_HAND);
        var view = body.getViewVector(1.0F).normalize();
        var eye = body.getEyePosition();
        var box = body.getBoundingBox().inflate(CANDIDATE_NET);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e :
                body.level().getEntitiesOfClass(LivingEntity.class, box, other -> other != body && other.isAlive())) {
            var key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            if (key == null || !LevelThreatSensor.hostileTypes().contains(key.toString())) {
                continue;
            }
            // Vanilla-aligned reach: squared distance from the eye to
            // the CLOSEST SURFACE of the target's bounding box - never
            // center-to-center, which read ~0.4 blocks longer than a
            // player could legitimately swing.
            double surfDistSq = e.getBoundingBox().distanceToSqr(eye);
            if (surfDistSq > MELEE_REACH_SURFACE * MELEE_REACH_SURFACE) {
                continue;
            }
            var targetCenter = e.position().add(0, e.getBbHeight() / 2, 0);
            var toTarget = targetCenter.subtract(eye);
            if (view.dot(toTarget.normalize()) < Math.cos(Math.toRadians(AIM_CONE_DEG))) {
                continue;
            }
            if (sightBlocked(eye, targetCenter) || lavaBetween(eye, targetCenter)) {
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
            LogUtils.getLogger().debug("[melee] HIT dist={} hp={}", bestDist, best.getHealth());
            // Vanilla mob melee in full: attribute damage plus
            // enchant bonus, knockback, Fire Aspect, and axe
            // shield-disable all live in Mob.doHurtTarget; the old
            // constant-3.0 hurt() skipped every one of them. The
            // zombie-scale base now rides the ATTACK_DAMAGE attribute
            // (3.0) with weapon modifiers added by the equipment
            // mirror.
            body.doHurtTarget(best);
        }
    }

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
    private boolean sightBlocked(Vec3 eye, Vec3 targetCenter) {
        var clip = body.level()
                .clip(new ClipContext(eye, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        if (clip.getType() == HitResult.Type.MISS) {
            return false;
        }
        return eye.distanceTo(clip.getLocation()) < eye.distanceTo(targetCenter) - MELEE_CLIP_SLACK;
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
    private boolean lavaBetween(Vec3 eye, Vec3 targetCenter) {
        var delta = targetCenter.subtract(eye);
        int steps = (int) Math.ceil(delta.length() / 0.5);
        for (int i = 1; i < steps; i++) {
            var at = eye.add(delta.scale((double) i / steps));
            if (body.level().getBlockState(BlockPos.containing(at)).is(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
