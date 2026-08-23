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
 * <p>Why the default AI cannot coexist with input driving
 * (empirically confirmed via body-probe, do not re-derive from
 * memory): Mob.serverAiStep runs navigation.tick(), then
 * customServerAiStep() (our write point), THEN moveControl.tick().
 * A default MoveControl sitting in WAIT executes setZza(0) in that
 * last slot, silently erasing the binding's drive every tick while
 * gravity keeps working - so the body falls but never walks. The
 * constructor therefore swaps in a no-op MoveControl; the binding is
 * the only locomotion writer. Stage 2 landmine: if A* or follow goals
 * ever need vanilla pathing again, the swap must be revisited as a
 * whole (drive through MoveControl instead of around it), not patched
 * by re-enabling it beside the binding.
 *
 * <p>Implementation note: constructed and ticked by the server only.
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
        // Neutralize MoveControl: Mob.serverAiStep runs moveControl.tick()
        // AFTER customServerAiStep, and its WAIT branch does
        // setZza(0) every tick - it would silently clobber the
        // binding's drive before travel ever sees it (confirmed
        // empirically: body-probe read zza=1 inside the AI step,
        // deltaMovement.x stayed 0). The binding is the only writer.
        this.moveControl = new MoveControl(this) {
            @Override
            public void tick() {
                // binding owns locomotion inputs; nothing to do
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
     * @param jump    true to request a jump this tick
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
        setXxa(driveStrafe);
        setZza(driveForward);
        // travel()'s magnitude source: normal mobs get this from
        // MoveControl.setSpeed(); with MoveControl swapped out, the
        // binding must supply it or zza drives a speed-0 body.
        setSpeed((float) getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
        if (driveJump && onGround()) {
            // Deliberate deviation from the vanilla flag path: writing
            // setJumping(true) and letting LivingEntity.aiStep consume
            // it never lifted this body (field verified true at write
            // time, zero vertical velocity ever applied - traced by
            // the gauntlet jump probe), so the binding fires
            // jumpFromGround directly, the exact call that consumer
            // would make. The one-shot reset keeps a held flag from
            // machine-gunning jumps every tick; steering re-raises it
            // each tick while the climb waypoint is still above us.
            jumpFromGround();
            driveJump = false;
        }
        if (hasPendingRotation) {
            setRot(targetYaw, targetPitch);
            setYRot(targetYaw);
            setYHeadRot(targetYaw);
            hasPendingRotation = false;
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }
}
