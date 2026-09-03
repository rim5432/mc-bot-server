package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.IdleLook;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Reflex claim construction (extracted from BotController's WMC
 * budget, 2026-08-27): the aim-and-dig pair shared by the seated
 * dig-family missions and the suffocation reflex, and the eat
 * select-and-use pair whose adapter rising edge consumes one item
 * per firing episode. Pure intent emission - no arbitration, no
 * routing; the controller decides WHEN, this class decides the
 * claim shapes.
 */
public final class ReflexClaimInjector {

    private final Actor actor;

    private final Supplier<CellPos> pose;

    /** Best-food hotbar slot for the EAT reflex; -1 until wired. */
    private IntSupplier eatSlot = () -> -1;

    /** Best-healing-potion hotbar slot for the DRINK reflex; -1 until wired. */
    private IntSupplier drinkSlot = () -> -1;

    /** Water-bucket hotbar slot for the MLG reflex; -1 until wired. */
    private IntSupplier mlgBucketSlot = () -> -1;

    /** Alternating press/release so a failed placement retries. */
    private boolean mlgPulse;

    /**
     * Creates the injector over one actor and body position.
     *
     * @param actor claim surface; never null
     * @param pose  body cell supplier for aim geometry; never null
     */
    public ReflexClaimInjector(Actor actor, Supplier<CellPos> pose) {
        this.actor = java.util.Objects.requireNonNull(actor, "actor");
        this.pose = java.util.Objects.requireNonNull(pose, "pose");
    }

    /**
     * Feed the eat reflex's execution slot: the hotbar slot holding
     * the best food, or -1 when none. Defaults to -1 so an unwired
     * rig degrades an EAT decision to the plain hold - stale slot
     * data must not mint a phantom bite.
     *
     * @param supplier best-food hotbar slot 0..8, or -1; never null
     */
    void setEatSlotSupplier(IntSupplier supplier) {
        this.eatSlot = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /**
     * The eat slot the supplier currently reports, or -1 when no food is
     * sensed. Package-private: BotController reads it for EAT_STARTED
     * disclosure (ledger 34 extension).
     *
     * @return 0..8 when food is sensed, -1 otherwise
     */
    int currentEatSlot() {
        return eatSlot.getAsInt();
    }

    /**
     * Feed the drink reflex's execution slot: the hotbar slot holding
     * the best healing potion, or -1 when none. Defaults to -1 so an
     * unwired rig degrades a DRINK decision to the plain hold - stale
     * slot data must not mint a phantom sip.
     *
     * @param supplier best-potion hotbar slot 0..8, or -1; never null
     */
    void setDrinkSlotSupplier(IntSupplier supplier) {
        this.drinkSlot = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /**
     * The drink slot the supplier currently reports, or -1 when no
     * potion is sensed. Package-private: BotController reads it for
     * DRINK_STARTED disclosure.
     *
     * @return 0..8 when a potion is sensed, -1 otherwise
     */
    int currentDrinkSlot() {
        return drinkSlot.getAsInt();
    }

    /**
     * Feed the MLG reflex's execution slot: the water-bucket hotbar
     * slot, or -1 when none. Defaults to -1 so an unwired rig
     * degrades a WATER_BUCKET decision to silence - stale slot data
     * must not mint a phantom placement.
     *
     * @param supplier water-bucket hotbar slot 0..8, or -1; never null
     */
    void setMlgBucketSlotSupplier(IntSupplier supplier) {
        this.mlgBucketSlot = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /**
     * Inject the DIG and EAT extra claims for a winning reflex
     * decision: DIG carries its own geometry (ROT aim + INTERACT dig
     * claims, re-issued every firing tick because claims expire per
     * flush); EAT carries the select-and-use pair. Missing data (no
     * dig target, no sensed food slot) injects nothing - stale
     * inputs must not mint a dig-at-null or a phantom bite.
     *
     * @param decision the winning reflex decision; never null
     */
    void injectAux(SurvivalReflexLayer.ReflexDecision decision) {
        if (decision.action() == ReflexAction.DIG && decision.target() != null) {
            aimAndDig(decision.priority(), ReflexAction.REFLEX_OWNER_PREFIX + decision.ruleName(), decision.target());
        }
        if (decision.action() == ReflexAction.WATER_BUCKET && decision.target() != null) {
            int bucketSlot = mlgBucketSlot.getAsInt();
            if (bucketSlot >= 0) {
                String owner = ReflexAction.REFLEX_OWNER_PREFIX + decision.ruleName();
                // MLG needs a straight-down look (pitch=90) so the bucket
                // raycast hits the block directly under the feet. IdleLook.pitchTo
                // clamps to PITCH_LIMIT_DEG=60 (an idle-glance safeguard), which
                // would tilt the ray 30 deg off vertical and place the water
                // several blocks away — the fall damage is then uncanceled.
                // Yaw is irrelevant for a vertical ray but kept at the target
                // bearing for presentation consistency.
                Vec3 from = cellCenter(pose.get());
                Vec3 to = cellCenter(decision.target());
                actor.submit(new Claim(
                        Channel.ROT, decision.priority(), owner, new Intent.Look(IdleLook.yawTo(from, to), 90f)));
                actor.submit(new Claim(Channel.SLOT, decision.priority(), owner, new Intent.SelectSlot(bucketSlot)));
                // Pulsed press: a rising edge every other tick, so a
                // placement that missed (pose lag, ray miss) retries
                // instead of hanging on a stuck press.
                mlgPulse = !mlgPulse;
                actor.submit(new Claim(Channel.USE, decision.priority(), owner, new Intent.Use(mlgPulse)));
            }
        }
        int slot =
                switch (decision.action()) {
                    case EAT -> eatSlot.getAsInt();
                    case DRINK -> drinkSlot.getAsInt();
                    default -> -1;
                };
        if (slot >= 0) {
            String owner = ReflexAction.REFLEX_OWNER_PREFIX + decision.ruleName();
            actor.submit(new Claim(Channel.SLOT, decision.priority(), owner, new Intent.SelectSlot(slot)));
            actor.submit(new Claim(Channel.USE, decision.priority(), owner, new Intent.Use(true)));
        }
    }

    /**
     * The aim-and-dig claim pair shared by the two DIG drivers (the
     * seated dig-family mission and the suffocation reflex). The
     * INTERACT claim expires at each flush, so a driver re-issues
     * the pair every tick it wants digging to continue - silence
     * resets the adapter's accumulated destroy progress. The ROT aim
     * is presentation only (the executor is server-authoritative and
     * never reads facing, exactly like vanilla's
     * ServerPlayerGameMode); cell-center math keeps it free of
     * eye-height constants.
     *
     * @param priority the claiming driver's priority
     * @param holder   stable claim owner, e.g. {@code mission:dig:t3}
     * @param target   the cell to dig; never null
     */
    void aimAndDig(int priority, String holder, CellPos target) {
        Vec3 from = cellCenter(pose.get());
        Vec3 to = cellCenter(target);
        actor.submit(new Claim(
                Channel.ROT, priority, holder, new Intent.Look(IdleLook.yawTo(from, to), IdleLook.pitchTo(from, to))));
        actor.submit(new Claim(Channel.INTERACT, priority, holder, new Intent.Dig(target)));
    }

    /** Center of one block cell; presentation-grade geometry. */
    private static Vec3 cellCenter(CellPos cell) {
        return new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
    }
}
