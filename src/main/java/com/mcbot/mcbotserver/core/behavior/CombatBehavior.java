package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.combat.WeaponCatalog;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.Objects;

/**
 * Combat micro-execution per boundaries.md decision 11: aim at the
 * ordered target and time swings. Deliberately owns NO locomotion -
 * the chase is a {@link com.mcbot.mcbotserver.core.pathing} concern
 * driven by the directive's goal; this behavior only adds ROT aim and
 * USE swing claims on top.
 *
 * <p>Contract: see boundaries.md decision 11 and decision 14 (four
 * channels - melee rides USE as "act with main hand"; the adapter
 * resolves what a swing hits). A directive without a combat order is
 * ignored on the first line: goto missions must feel nothing from
 * this behavior existing.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (behavior owns micro-execution)
public final class CombatBehavior implements Behavior {

    /** Distance from the aim point within which a swing may fire. */
    public static final double ATTACK_REACH = 3.0;

    /**
     * Horizontal distance below which the aim bearing is held, not
     * recomputed - overlapping a target makes direction noise. The
     * threshold sits just under the player's half-width hitbox
     * (0.6 wide / 2 = 0.3 each side, plus a little margin for the
     * cap cell) so the body has to be measurably out of overlap
     * before the bearing is allowed to swing again; below it the
     * 180-degree-per-tick yaw flip dominates the atan2 signal and
     * every dot-product check against the aim cone fails.
     */
    public static final double AIM_MIN_HORIZONTAL = 0.5;

    /** Minimum ticks between swings (vanilla invulnerability scale). */
    public static final int ATTACK_COOLDOWN_TICKS = 10;

    /**
     * Ticks a swing window stays open after reach left the gate.
     * Edge-jittering targets (knockback, strafe) otherwise waste the
     * cooldown expiry whenever it lands on an out-of-range tick; the
     * hold bridges those gaps without widening honest reach.
     */
    public static final int AIM_HOLD_TICKS = 3;

    private final String name;
    private final BodyPositionSource positionSource;
    private final WeaponCatalog weapons;
    private int ticksSinceSwing = ATTACK_COOLDOWN_TICKS;
    // Starts EXPIRED: a target that has never been in reach earns no
    // hold memory - only an actual in-reach sighting opens the window.
    private int ticksSinceInReach = AIM_HOLD_TICKS + 1;
    private boolean pressLatched;
    private float lastYaw;

    /**
     * Creates a combat behavior over one body position source.
     *
     * @param name       stable identity for claims; never null or blank
     * @param positionSource body position accessor; never null
     */
    public CombatBehavior(String name, BodyPositionSource positionSource) {
        this(name, positionSource, WeaponCatalog.none());
    }

    /**
     * Creates a combat behavior that also holds the best hotbar
     * weapon while fighting.
     *
     * @param name            stable identity for claims; never null or
     *                        blank
     * @param positionSource  body position accessor; never null
     * @param weapons         weapon ranking; never null
     */
    public CombatBehavior(String name, BodyPositionSource positionSource, WeaponCatalog weapons) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.weapons = Objects.requireNonNull(weapons, "weapons");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ExecutionReport tick(WorldView world, Directive directive, Actor actor) {
        if (directive == null || directive.overrides().combat() == null) {
            return ExecutionReport.running();
        }

        holdBestWeapon(world, actor);

        Vec3 position = positionSource.get();
        CellPos aimCell = aimPointOf(directive.goal());
        double tx = aimCell.x() + 0.5;
        double ty = aimCell.y() + 1.0;
        double tz = aimCell.z() + 0.5;

        double dx = tx - position.x();
        double dy = ty - position.y();
        double dz = tz - position.z();

        // Aim degeneracy guard: when standing on top of the target,
        // horizontal direction is noise and the computed yaw flips
        // 180 degrees every tick. Hold the last bearing instead.
        double horizontal = Math.hypot(dx, dz);
        float yaw = lastYaw;
        if (horizontal >= AIM_MIN_HORIZONTAL) {
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            lastYaw = yaw;
        }
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 0.001)));
        actor.submit(new Claim(Channel.ROT, 20, name, new Intent.Look(yaw, pitch)));

        // Swing pacing: hold the release until cooldown expires so the
        // adapter sees exactly one rising edge per attack window. The
        // reach memory lets the window survive brief excursions past
        // the gate instead of starving on edge jitter.
        boolean released = !pressLatched;
        ticksSinceSwing++;
        double reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (reach <= ATTACK_REACH) {
            ticksSinceInReach = 0;
        } else {
            ticksSinceInReach++;
        }
        boolean inReachMemory = reach <= ATTACK_REACH || ticksSinceInReach <= AIM_HOLD_TICKS;
        if (released && ticksSinceSwing >= ATTACK_COOLDOWN_TICKS && inReachMemory) {
            actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(true)));
            pressLatched = true;
            ticksSinceSwing = 0;
        } else if (pressLatched) {
            actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
            pressLatched = false;
        }
        return ExecutionReport.running();
    }

    /**
     * Hold the hotbar's highest per-hit weapon while fighting: mob
     * melee has no attack-speed scaling (the strength ticker is
     * Player-only), so per-hit damage is the whole ranking - an axe
     * outranks a same-tier sword. One SLOT claim whenever the best
     * slot differs from the current selection; SelectSlot is recorded
     * body state, so an unchanged selection costs nothing per tick.
     */
    private void holdBestWeapon(WorldView world, Actor actor) {
        InventoryView inventory = world.getInventory();
        int best = -1;
        float bestDamage = 0f;
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            var item = inventory.main().get(slot);
            if (item.isEmpty()) {
                continue;
            }
            float damage = weapons.perHitDamage(item.itemId());
            if (damage > bestDamage) {
                best = slot;
                bestDamage = damage;
            }
        }
        if (best >= 0 && best != inventory.selectedSlot()) {
            actor.submit(new Claim(Channel.SLOT, 20, name, new Intent.SelectSlot(best)));
        }
    }

    /**
     * Where to aim for the current directive's target. Exhaustive over
     * the sealed goal algebra; both goal forms carry the target cell.
     *
     * @param goal the directive's locomotion goal; never null
     * @return the cell whose center the body should face; never null
     */
    private static CellPos aimPointOf(Goal goal) {
        if (goal instanceof GoalBlock b) {
            return b.target();
        }
        if (goal instanceof GoalNear n) {
            return n.center();
        }
        throw new IllegalStateException("unhandled goal variant: " + goal.getClass());
    }
}
