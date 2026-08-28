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
 * resolves what a swing hits; amended by ledger 36: a held USE draw
 * charges and the falling edge releases, which is how the bow fires).
 * A directive without a combat order is ignored on the first line:
 * goto missions must feel nothing from this behavior existing.
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

    /** Full bow draw in ticks before release (vanilla crit threshold). */
    public static final int BOW_CHARGE_TICKS = 20;

    /** Engagement cap for bow fire (skeleton-parity range band). */
    public static final double BOW_RANGE = 15.0;

    /**
     * Arrow-drop approximation at full draw, blocks of drop per
     * block² of range (v = 3 b/t, g = 0.05 b/t² gives 0.5·g/v²). The
     * aim point rises by drop·range² - a constant-velocity parabola
     * fit, honest to within a block across the bow band.
     */
    public static final double ARROW_DROP_PER_BLOCK_SQUARED = 0.0028;

    private static final String BOW_ID = "minecraft:bow";

    private static final java.util.Set<String> ARROW_IDS =
            java.util.Set.of("minecraft:arrow", "minecraft:tipped_arrow", "minecraft:spectral_arrow");

    private final String name;
    private final BodyPositionSource positionSource;
    private final WeaponCatalog weapons;
    private int ticksSinceSwing = ATTACK_COOLDOWN_TICKS;
    // Starts EXPIRED: a target that has never been in reach earns no
    // hold memory - only an actual in-reach sighting opens the window.
    private int ticksSinceInReach = AIM_HOLD_TICKS + 1;
    private boolean pressLatched;
    private float lastYaw;
    private boolean drawing;
    private int chargeTicks;

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
            // A stale draw must not outlive its directive.
            abortDraw(actor);
            return ExecutionReport.running();
        }

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

        int bowSlot = rangedSlot(world, Math.sqrt(dx * dx + dy * dy + dz * dz));
        boolean ranging = bowSlot >= 0;
        // Arrow drop raises the effective aim point; zero on the melee
        // path where the swing resolves without ballistics.
        double lift = ranging ? ARROW_DROP_PER_BLOCK_SQUARED * (dx * dx + dy * dy + dz * dz) : 0.0;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy + lift, Math.max(horizontal, 0.001)));
        actor.submit(new Claim(Channel.ROT, 20, name, new Intent.Look(yaw, pitch)));

        if (ranging) {
            // The bow owns SLOT while ranging: the melee ranking would
            // switch off a bow every tick (bows carry no attack-damage
            // modifier), so holdBestWeapon is suppressed, not raced.
            InventoryView inventory = world.getInventory();
            if (inventory.selectedSlot() != bowSlot) {
                actor.submit(new Claim(Channel.SLOT, 20, name, new Intent.SelectSlot(bowSlot)));
                return ExecutionReport.running();
            }
            if (pressLatched) {
                // A melee swing window was open when ranging began -
                // close it before the draw, one USE edge per tick.
                actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
                pressLatched = false;
                return ExecutionReport.running();
            }
            // Draw pacing: sustain Use(true) across the charge (a USE
            // gap reads as a release adapter-side), then one false tick
            // fires BowItem.releaseUsing with the accumulated charge.
            if (!drawing) {
                actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(true)));
                drawing = true;
                chargeTicks = 1;
            } else if (++chargeTicks > BOW_CHARGE_TICKS) {
                actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
                drawing = false;
                chargeTicks = 0;
            } else {
                actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(true)));
            }
            return ExecutionReport.running();
        }
        abortDraw(actor);

        holdBestWeapon(world, actor);

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
     * Release an in-flight draw, if any. Called on every exit from the
     * ranged path - a drawn bow with no watcher would hold the charge
     * forever.
     *
     * @param actor claim surface; never null
     */
    private void abortDraw(Actor actor) {
        if (drawing) {
            actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
            drawing = false;
            chargeTicks = 0;
        }
    }

    /**
     * The hotbar bow slot when ranged fire is the right answer: a bow
     * carried in the hotbar, arrows anywhere in the inventory, and the
     * target beyond melee reach but inside the bow band.
     *
     * @param world read surface for the inventory; never null
     * @param reach current distance to the aim point
     * @return the bow's hotbar slot, or -1 to answer with melee
     */
    private static int rangedSlot(WorldView world, double reach) {
        if (reach <= ATTACK_REACH || reach > BOW_RANGE) {
            return -1;
        }
        return rangedLoadoutSlot(world);
    }

    /**
     * The hotbar bow slot when a ranged loadout exists: a bow carried
     * in the hotbar and arrows anywhere in the inventory. Process-tier
     * combat planning (DefendProcess) reads this to decide whether a
     * ranged hostile can be engaged at all, so it deliberately knows
     * nothing about reach or band - distance gates are the caller's.
     *
     * @param world read surface for the inventory; never null
     * @return the bow's hotbar slot, or -1 when the loadout is missing
     */
    public static int rangedLoadoutSlot(WorldView world) {
        InventoryView inventory = world.getInventory();
        int bow = -1;
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            var item = inventory.main().get(slot);
            if (!item.isEmpty() && BOW_ID.equals(item.itemId())) {
                bow = slot;
                break;
            }
        }
        if (bow < 0) {
            return -1;
        }
        for (var item : inventory.main()) {
            if (!item.isEmpty() && ARROW_IDS.contains(item.itemId())) {
                return bow;
            }
        }
        return -1;
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
