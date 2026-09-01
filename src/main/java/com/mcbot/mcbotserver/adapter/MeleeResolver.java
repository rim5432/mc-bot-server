package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.capability.Feature;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
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
     * Game time of the last melee swing that reset the cooldown. Every
     * USE rising edge resets it (vanilla parity: spamming clicks never
     * builds a full charge). -1 means no swing has happened yet.
     */
    private long lastSwingTick = -1;

    /**
     * @param body the swinging body; never null
     */
    MeleeResolver(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * Attack cooldown in ticks, derived from the held item's
     * ATTACK_SPEED attribute. Vanilla formula:
     * {@code 1.0 / attackSpeed * 20.0}. Sword (1.6) = 12.5 ticks,
     * axe (0.8-1.0) = 20-25, empty hand (4.0) = 5. The attribute is
     * read live because the SLOT channel may swap the held item mid-
     * combat; the equipment mirror applies the item's attribute
     * modifiers via detectEquipmentUpdates.
     *
     * @return cooldown in ticks; never negative
     */
    @Feature(
            id = "combat.melee.attack_cooldown",
            face = "combat.melee",
            description =
                    "Attack cooldown derived live from the held item's ATTACK_SPEED attribute: 1.0 / speed * 20" +
                    " ticks. Sword=12.5, axe=20-25, empty hand=5. Read live because the SLOT channel may swap" +
                    " weapons mid-combat.",
            vanillaRef = "Player.getAttackStrengthScale (decompiled 1.20.1)",
            deviation =
                    "Bot only deals damage at full charge; partial-charge damage is a Player-only affordance the" +
                    " harness has no use for. Unready swings do not reset the cooldown to avoid a zero-damage" +
                    " lockout from fast pulse.")
    private double getAttackCooldownTicks() {
        double speed = body.getAttributeValue(Attributes.ATTACK_SPEED);
        return 1.0 / speed * 20.0;
    }

    /**
     * Whether the current swing is at full charge. Since the bot only
     * deals damage on a fully-charged swing (partial-charge damage is
     * a Player-only affordance the harness has no use for), this gates
     * every hurt attempt. A never-swung body is always ready.
     *
     * @return true when enough ticks have elapsed since the last swing
     */
    private boolean isAttackReady() {
        if (lastSwingTick < 0) {
            return true;
        }
        return body.level().getGameTime() - lastSwingTick >= getAttackCooldownTicks();
    }

    /**
     * Critical hit conditions, mirrored from vanilla Player.attack
     * (decompiled 1.20.1): the swing must be fully charged (>0.9
     * strength scale — always true here because isAttackReady gates),
     * the body must be falling (fallDistance > 0, not onGround), not
     * in water, not blind, not on a climbable block, not riding, and
     * not sprinting. Sprinting suppresses crits because a sprint-attack
     * is a knockback sweep instead.
     *
     * @return true when the next hit should be a critical strike
     */
    @Feature(
            id = "combat.melee.crit_hit",
            face = "combat.melee",
            description =
                    "Critical hit conditions mirrored from vanilla: falling (fallDistance>0, not onGround) + not" +
                    " in water + not blind + not on climbable block + not riding + not sprinting. 1.5x damage" +
                    " multiplier when true.",
            vanillaRef = "Player.attack critical-hit branch (decompiled 1.20.1)")
    private boolean isCriticalHit() {
        return body.fallDistance > 0.0F
                && !body.onGround()
                && !body.isInWater()
                && !body.hasEffect(MobEffects.BLINDNESS)
                && !body.onClimbable()
                && !body.isPassenger()
                && !body.isSprinting();
    }

    /**
     * Resolve one USE press: always swing (visual); additionally hurt
     * the nearest qualifying hostile if the attack cooldown has
     * elapsed. Only a ready swing resets the cooldown - vanilla
     * resets every attack but pays scaled partial damage, while this
     * resolver pays nothing below full charge, so resetting an
     * unready swing would let a fast pulse defer the window forever.
     * A harness pulsing faster than the weapon's attack speed gets
     * one landed hit per cooldown window, vanilla's effective
     * output.
     */
    @Feature(
            id = "combat.melee.reach_and_cone",
            face = "combat.melee",
            description =
                    "Melee hit resolution: 3.0 eye-to-surface reach (not center-to-center), 45-degree aim cone," +
                    "nearest hostile wins. Candidate net prefiltered at reach+1.5 before the exact" +
                    " surface-distance gate.",
            vanillaRef = "Player.attack reach metric (decompiled 1.20.1)",
            deviation =
                    "Cone test instead of vanilla ray-clip because EntitySnapshot granularity is block-level and a" +
                    " ray would whiff on sub-cell offsets. Surface-distance reach matches vanilla; old" +
                    " center-to-center 3.5 read ~0.4 blocks too far.")
    void onUsePress() {
        body.swing(InteractionHand.MAIN_HAND);
        boolean ready = isAttackReady();
        // Reset only a ready swing. Vanilla resets the charge ticker on
        // every attack but pays scaled partial damage; this resolver
        // pays zero below full charge, so resetting an unready swing
        // lets a caller pulsing faster than the item cooldown defer
        // the window forever (CombatBehavior paces 10 ticks, a sword's
        // cooldown is 12.5) - a zero-damage lockout.
        if (!ready) {
            return;
        }
        lastSwingTick = body.level().getGameTime();
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
            // production log needs at INFO. The faa9aae standoff
            // bug was found by walking these lines; a future tuning
            // pass will reach for the same lever.
            LogUtils.getLogger().debug("[melee] HIT dist={} hp={}", bestDist, best.getHealth());
            performMeleeAttack(best);
        }
    }

    /**
     * Crit-aware melee attack: replicates Mob.doHurtTarget's full
     * chain (attribute damage + enchant bonus, knockback, Fire Aspect,
     * post-hurt enchant effects) with a 1.5x critical-hit multiplier
     * when {@link #isCriticalHit()} is true. doHurtTarget itself cannot
     * carry a damage multiplier (it computes f internally), so the
     * chain is inlined here — the same deviation class as DigExecutor's
     * manual break sequence, where the engine's one-size-fits-all call
     * loses context the bot needs.
     *
     * <p>Sweep attack: non-crit fully-charged hits additionally sweep
     * bystanders - see {@link #performSweepAttack} for the vanilla
     * conditions and formulas.
     *
     * @param target the hurt entity; never null
     */
    @Feature(
            id = "combat.melee.damage_chain",
            face = "combat.melee",
            description =
                    "Full melee damage chain: ATTACK_DAMAGE attribute + enchantment bonus + Fire Aspect +" +
                    " knockback + post-hurt enchant effects. setLastHurtByPlayer makes the kill award vanilla XP" +
                    " orbs (bot is PathfinderMob, mobAttack source does not set this flag).",
            vanillaRef = "Mob.doHurtTarget + LivingEntity.die XP award (decompiled 1.20.1)",
            deviation =
                    "doHurtTarget cannot carry a damage multiplier (computes f internally), so the crit-aware" +
                    " chain is inlined here — same deviation class as DigExecutor's manual break sequence.")
    private void performMeleeAttack(LivingEntity target) {
        float damage = (float) body.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float knockback = (float) body.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
        damage += EnchantmentHelper.getDamageBonus(body.getMainHandItem(), target.getMobType());
        knockback += EnchantmentHelper.getKnockbackBonus(body);
        boolean crit = isCriticalHit();
        if (crit) {
            damage *= 1.5F;
        }
        int fireAspect = EnchantmentHelper.getFireAspect(body);
        if (fireAspect > 0) {
            target.setSecondsOnFire(fireAspect * 4);
        }
        // Mark the target as hurt-by-player so vanilla LivingEntity.die
        // awards XP orbs (it checks lastHurtByPlayerTime > 0). The bot
        // is a PathfinderMob, so the mobAttack damage source does not set
        // this flag; the BotPlayerFacade fills the typed Player slot
        // without being spawned. die() only reads the timestamp, never
        // calls facade methods.
        target.setLastHurtByPlayer(body.playerFacade());
        boolean hit = target.hurt(body.damageSources().mobAttack(body), damage);
        if (hit) {
            if (knockback > 0.0F) {
                target.knockback(
                        knockback * 0.5F,
                        Mth.sin(body.getYRot() * ((float) Math.PI / 180F)),
                        -Mth.cos(body.getYRot() * ((float) Math.PI / 180F)));
                body.setDeltaMovement(body.getDeltaMovement().multiply(0.6D, 1.0D, 0.6D));
                body.setSprinting(false);
            }
            EnchantmentHelper.doPostHurtEffects(target, body);
            if (crit) {
                // 2002 = critical-hit particle burst (vanilla
                // Player.attack spawns it at the target's block pos).
                body.level().levelEvent(2002, target.blockPosition(), 0);
            }
            performSweepAttack(target, damage, crit);
            body.setLastHurtMob(target);
        }
        // doPostDamageEffects runs on hit or miss (vanilla doHurtTarget
        // calls it unconditionally after the hurt attempt).
        EnchantmentHelper.doPostDamageEffects(body, target);
    }

    /**
     * Sword sweep (vanilla Player.attack lines 1219-1274, decompiled
     * 1.20.1): when the hit is fully charged (always true here), not
     * a crit, not sprinting, on ground, and the held item performs
     * SWORD_SWEEP, the attack also hits every LivingEntity in the
     * sword's sweep hit box (excluding self and the main target,
     * non-allied only). Sweep damage = 1.0 + SweepingEdge ratio *
     * main damage; knockback = 0.4; plays PLAYER_ATTACK_SWEEP.
     *
     * @param target the main hit target, excluded from the sweep; never null
     * @param damage the main-hit damage after the crit multiplier
     * @param crit   whether the main hit was a critical strike
     */
    @Feature(
            id = "combat.melee.sweep_attack",
            face = "combat.melee",
            description =
                    "Sword sweep: fully charged + not crit + not sprinting + onGround + SWORD_SWEEP action. Sweep" +
                    " damage = 1.0 + SweepingEdge ratio * main damage; knockback = 0.4; plays PLAYER_ATTACK_SWEEP." +
                    "Bystanders in getSweepHitBox (excluding self and main target, non-allied) take the sweep hit.",
            vanillaRef = "Player.attack lines 1219-1274 (decompiled 1.20.1)")
    private void performSweepAttack(LivingEntity target, float damage, boolean crit) {
        if (!crit
                && !body.isSprinting()
                && body.onGround()
                && body.getMainHandItem().canPerformAction(net.minecraftforge.common.ToolActions.SWORD_SWEEP)) {
            float sweepDamage = 1.0F + EnchantmentHelper.getSweepingDamageRatio(body) * damage;
            // getSweepHitBox requires a Player (Forge extension); sync
            // the facade position to the body so the box lands in front
            // of the actual body, not the facade's stale construction pos.
            var facade = body.playerFacade();
            facade.syncPosition();
            var sweepBox = body.getMainHandItem().getSweepHitBox(facade, target);
            for (LivingEntity bystander : body.level().getEntitiesOfClass(LivingEntity.class, sweepBox)) {
                if (bystander != body && bystander != target && !body.isAlliedTo(bystander)) {
                    bystander.knockback(
                            0.4F,
                            Mth.sin(body.getYRot() * ((float) Math.PI / 180F)),
                            -Mth.cos(body.getYRot() * ((float) Math.PI / 180F)));
                    bystander.hurt(body.damageSources().mobAttack(body), sweepDamage);
                }
            }
            body.level()
                    .playSound(
                            null,
                            body.getX(),
                            body.getY(),
                            body.getZ(),
                            net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                            body.getSoundSource(),
                            1.0F,
                            1.0F);
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
    @Feature(
            id = "combat.line_of_sight.terrain_clip",
            face = "combat.line_of_sight",
            description =
                    "Terrain line-of-sight check: vanilla ClipContext with Block.COLLIDER / Fluid.NONE from eye to" +
                    " target center. A hit before the target (minus 0.35 grazing slack) blocks the swing. Lava is" +
                    " transparent to this clip — see lavaBetween for the complementary check.",
            vanillaRef = "Player.hasLineOfSight (decompiled 1.20.1)")
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
    @Feature(
            id = "combat.line_of_sight.lava_opaque",
            face = "combat.line_of_sight",
            description =
                    "Lava is treated as opaque for melee line-of-sight: 0.5-block point sampling along" +
                    " eye-to-target segment; any point in lava blocks the swing. Complementary to sightBlocked" +
                    " because vanilla's ClipContext with Fluid.NONE treats lava as transparent.",
            vanillaRef =
                    "No direct vanilla counterpart — vanilla melee raycast uses Fluid.NONE and does not" +
                    " special-case lava",
            deviation =
                    "Bot-specific: melee across lava is impossible, so pretending otherwise turns the bot into a" +
                    " sandbag swinging at the far shore. Water stays transparent by v1 semantics.")
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
