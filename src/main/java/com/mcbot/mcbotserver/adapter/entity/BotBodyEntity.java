package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.Level;

/**
 * The physical body: a vanilla-pathfinding-capable mob whose locomotion
 * is driven exclusively by per-tick input writes from the binding.
 *
 * <p>Spike outcome (workplan follow-up, Stage 1 review pending): a
 * custom PathfinderMob carrier instead of a ServerPlayer subclass.
 * Rationale: the vertical slice needs engine-owned physics and nothing
 * else; player-parity machinery (FakeConnection, chunk-ticket mixin,
 * inventory semantics) is deferred until a real requirement appears.
 * Vanilla {@code travel()} remains the only thing that moves this body
 * — boundary A holds because every write below is an intent echo of an
 * Actor claim, never self-computed physics.
 *
 * <p>Why the default AI controls cannot coexist with input driving
 * (empirically confirmed via body-probe, do not re-derive from
 * memory): Mob.aiStep() runs goalSelector.tick(), navigation.tick(),
 * customServerAiStep() (our write point), THEN moveControl.tick(),
 * lookControl.tick(), jumpControl.tick(). Each default control sits
 * in a slot AFTER the binding's write and silently clobbers shared
 * state: MoveControl.WAIT does setZza(0); LookControl.resetXRotOnTick
 * forces setXRot(0) (wipes non-zero pitch); JumpControl does
 * setJumping(false). The constructor therefore swaps all three for
 * no-ops; the binding is the sole writer of locomotion, look, and
 * jump state. Stage 2 landmine: if A* or follow goals ever need
 * vanilla pathing again, all three swaps must be revisited as a
 * whole (drive through the controls instead of around them), not
 * patched by re-enabling one beside the binding.
 *
 * <p>Implementation note: constructed and ticked by the server only.
 *
 * <p>World treatment (user ruling 2026-08-25): the world treats the
 * bot as a player wherever the engine asks "is this a player".
 * Hostile targeting is one such place - vanilla sight-aggro goals
 * match {@code Player.class} entity scans, which structurally cannot
 * see a PathfinderMob. The presence pass below closes that gap from
 * the carrier side: monsters with line of sight and no other target
 * acquire this body exactly as they would acquire a player, at their
 * own follow range. Menu/interaction player-ness is the OTHER axis
 * and stays with the {@code BotPlayerFacade} plan (issue 0007 Path
 * A) - the facade answers typed parameters, never entity scans.
 */
// contract: see boundaries.md section A (Actor is intents-only) and
// decision 2 as amended by the entity-binding spike follow-up
public final class BotBodyEntity extends PathfinderMob {

    private float driveForward;
    private float driveStrafe;
    private boolean driveJump;
    private boolean hasPendingRotation;
    private float targetYaw;
    private float targetPitch;
    private int presenceCooldown;

    /**
     * Ticks between presence scans. The scan queries a wide entity
     * box and ray-traces one line of sight per candidate; hostile
     * awareness does not need per-tick latency (vanilla targeting
     * goals also re-scan on their own cadence).
     */
    public static final int PRESENCE_SCAN_INTERVAL_TICKS = 20;

    /**
     * Half-width of the presence scan box, in blocks. Above every
     * vanilla follow range that matters (zombies 35, base attribute
     * 32); the per-monster attribute check below is the authority.
     */
    private static final double PRESENCE_SCAN_HALF_WIDTH = 40.0;

    /**
     * Hotbar selection echoed from the SLOT channel. A mob carrier has
     * no vanilla hotbar; the value is recorded state until equipment
     * semantics arrive (workplan Stage 2 inventory item).
     */
    public int selectedSlot;

    /**
     * Creates a body for the registered entity type.
     *
     * @param type  the registered type; never null
     * @param level the server level; never null
     */
    public BotBodyEntity(EntityType<? extends PathfinderMob> type,
                         Level level) {
        super(type, level);
        // Neutralize all three vanilla controls: the binding is the sole
        // writer of locomotion, look, and jump state. Each control runs
        // in Mob.aiStep() AFTER customServerAiStep() and would silently
        // clobber the binding's writes:
        //   - MoveControl.tick() WAIT branch: setZza(0) every tick
        //   - LookControl.tick(): resetXRotOnTick() forces setXRot(0),
        //     wiping any non-zero pitch the binding set (pitch-aim landmine)
        //   - JumpControl.tick(): setJumping(false) every tick, would
        //     zero a setJumping(true) before LivingEntity.aiStep() sees it
        // All three are Goal-system executors; with no goals registered and
        // the binding owning every input channel, they are concurrent writers
        // on shared state, not helpers. Anonymous no-ops match the existing
        // MoveControl pattern.
        this.moveControl = new MoveControl(this) {
            @Override
            public void tick() {
                // binding owns locomotion inputs; nothing to do
            }
        };
        this.lookControl = new net.minecraft.world.entity.ai.control
            .LookControl(this) {
            @Override
            public void tick() {
                // binding owns rotation via setRot/setYHeadRot; nothing to do
            }
        };
        this.jumpControl = new net.minecraft.world.entity.ai.control
            .JumpControl(this) {
            @Override
            public void tick() {
                // binding owns jump via jumpFromGround/jumpInFluid; nothing to do
            }
        };
        setPersistenceRequired();
    }

    /**
     * One tick of locomotion intent, written by the binding before the
     * physics step consumes it.
     *
     * @param forward -1..1 forward drive
     * @param strafe  -1..1 strafe drive
     * @param jump    true to request upward thrust this tick - a
     *                ground jump on land, buoyant ascent while in
     *                water or lava (issue 0004 F2 + lava branch)
     */
    public void setDrive(float forward, float strafe, boolean jump) {
        this.driveForward = forward;
        this.driveStrafe = strafe;
        this.driveJump = jump;
        if (!jump) {
            setJumping(false);
        }
    }

    /**
     * Queue an absolute rotation for the next tick.
     *
     * @param yawDeg   target body yaw in degrees
     * @param pitchDeg target view pitch in degrees
     */
    public void setTargetRotation(float yawDeg, float pitchDeg) {
        this.hasPendingRotation = true;
        this.targetYaw = yawDeg;
        this.targetPitch = pitchDeg;
    }

    @Override
    protected void customServerAiStep() {
        // Deliberately no super call: goal selectors and MoveControl
        // would fight the binding for zza. Inputs are applied directly
        // through LivingEntity's own public drive setters.
        //
        // ORDER MATTERS: Mob.setSpeed() overrides LivingEntity.setSpeed()
        // and additionally calls setZza(speed) — vanilla treats speed and
        // drive as synonymous for ordinary mobs. If setXxa/setZza run
        // before setSpeed, the speed write clobbers the binding's drive
        // every tick (zza=0.25 instead of 0), so an idle body walks
        // forward forever. setSpeed first, then re-apply drive values.
        setSpeed((float) getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
        setXxa(driveStrafe);
        setZza(driveForward);
        if (driveJump && isInLava() && !onGround()) {
            // Lava ascent: same direct-call deviation as the water branch
            // below — setJumping(true) produced zero vertical velocity on
            // this carrier (issue 0004 F2, same anomaly class). Mob.jumpInFluid
            // with LAVA_TYPE is the exact call the vanilla aiStep fluid branch
            // makes for lava. Lava is more viscous than water so the ascent
            // is slower, but a held flag keeps applying thrust each tick the
            // way MinimalReflex intends while crashed in lava.
            jumpInFluid(net.minecraftforge.common.ForgeMod.LAVA_TYPE.get());
        } else if (driveJump && isInWater() && !onGround()) {
            // Fluid ascent with no floor to push from: call the
            // engine's fluid jump directly. Runtime probes killed the
            // subtler route - writing setJumping(true) lets the vanilla
            // aiStep fluid branch read the flag but produced ZERO
            // vertical velocity on this carrier, the same anomaly as
            // the land case below (deep-pool gametest position trace,
            // issue 0004 F2). Mob.jumpInFluid is the exact call that
            // branch makes; invoking it here is the same class of
            // deviation as jumpFromGround. +0.3/tick while held,
            // matching a player holding space to surface.
            jumpInFluid(net.minecraftforge.common.ForgeMod.WATER_TYPE
                .get());
        } else if (driveJump && onGround()) {
            // Grounded (dry OR wading): fire jumpFromGround directly.
            // Two deliberate deviations live in this branch. First,
            // the vanilla flag path never lifted this body on LAND
            // (field verified true at write time, zero vertical
            // velocity ever applied - traced by the gauntlet jump
            // probe). Second, the flag route must NOT be used while
            // grounded in shallow water: aiStep throttles it with
            // noJumpDelay=10, and the slow hops stalled the trench
            // exit that per-tick direct jumps clear. The one-shot
            // reset keeps a held flag from machine-gunning jumps;
            // steering re-raises it each tick while the JumpUp
            // waypoint is still above us.
            jumpFromGround();
            driveJump = false;
        }
        if (hasPendingRotation) {
            // setRot does yaw%360 / pitch%360 normalization internally;
            // setYHeadRot aligns the head with the body so the model does
            // not lag one tick behind. Do NOT add a separate setYRot call
            // after setRot — it would overwrite the normalized value with
            // a raw angle and break the wrap-around for yaw outside [0,360).
            setRot(targetYaw, targetPitch);
            setYHeadRot(targetYaw);
            hasPendingRotation = false;
        }
        tickPresence();
    }

    /**
     * Carrier-side presence: monsters that can see this body treat it
     * as a player-shaped target. Deliberately NOT an Intent and not
     * on the Actor - being visible is a property of the body in the
     * world (same category as its collision box or its fire ticks),
     * not a decision any brain tier makes; the crashed latch keeps it
     * running for exactly that reason (a crashed body still gets
     * eaten). Only acquires targets that are currently unoccupied,
     * skips no-AI mobs (a frozen mob must stay frozen), and honors
     * each monster's own follow range plus a line-of-sight ray, so a
     * wall between the body and a monster still hides it.
     */
    private void tickPresence() {
        if (--presenceCooldown > 0) {
            return;
        }
        presenceCooldown = PRESENCE_SCAN_INTERVAL_TICKS;
        for (net.minecraft.world.entity.monster.Monster monster
                : level().getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Monster.class,
                    getBoundingBox().inflate(PRESENCE_SCAN_HALF_WIDTH),
                    m -> m.isAlive() && !m.isNoAi()
                        && m.getTarget() == null)) {
            double follow = monster.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes
                    .FOLLOW_RANGE);
            if (monster.distanceToSqr(this) > follow * follow) {
                continue;
            }
            if (!monster.getSensing().hasLineOfSight(this)) {
                continue;
            }
            monster.setTarget(this);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }
}
