package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
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
 * <p>Why serverAiStep is overridden to nothing: Mob's AI step runs
 * MoveControl, which zeroes zza every tick and would silently cancel
 * the binding's inputs. Skipping it keeps LivingEntity's physics chain
 * (aiStep -> travel) intact while making the body fully input-driven —
 * the same trick Numen's InputDriver plays against the player tick,
 * documented in numen-notes.md section 4.
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
        if (driveJump) {
            setJumping(true);
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
