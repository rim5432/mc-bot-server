package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.goal.Goals;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.combat.RangedLoadouts;
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
 * resolves what a swing hits; amended by ledger 37: a held USE draw
 * charges and the falling edge releases, which is how the bow fires).
 * A directive without a combat order is ignored on the first line:
 * goto missions must feel nothing from this behavior existing.
 *
 * <p>Range routing is stateful, not a per-tick distance reflex. A
 * draw past the commit threshold is finished, not aborted, when the
 * range verdict flips - a target oscillating across ATTACK_REACH
 * would otherwise restart the draw every approach tick and never
 * release at all. And under the weapon-aware configuration the bow
 * answers at every distance while nothing in the hotbar outranks it
 * in melee: a bow-only carrier keeps firing point-blank instead of
 * clubbing for fist-tier damage.
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

    /**
     * Draw-commit threshold: a draw charged this far is finished, not
     * aborted, when the range verdict flips to melee. Half the full
     * draw keeps the paid-in charge bounded at one draw and the
     * remaining wait under half a draw - below it, aborting is cheap
     * enough that a target oscillating across ATTACK_REACH loses
     * nothing by an immediate swing.
     */
    public static final int BOW_COMMIT_TICKS = BOW_CHARGE_TICKS / 2;

    /** Engagement cap for bow fire (skeleton-parity range band). */
    public static final double BOW_RANGE = 15.0;

    /**
     * Arrow-drop approximation at full draw, blocks of drop per
     * block² of range (v = 3 b/t, g = 0.05 b/t² gives 0.5·g/v²). The
     * aim point rises by drop·range² - a constant-velocity parabola
     * fit, honest to within a block across the bow band.
     */
    public static final double ARROW_DROP_PER_BLOCK_SQUARED = 0.0028;

    private final String name;
    private final BodyPositionSource positionSource;
    private final WeaponCatalog weapons;
    /**
     * The weapon-aware policy: inside ATTACK_REACH the bow keeps
     * answering while nothing in the hotbar outranks it in melee. The
     * no-catalog configuration keeps the legacy melee band - without
     * weapon knowledge the behavior cannot tell a sword from a rock,
     * so it must not hijack the swing rim on the guess that the bow
     * is the best club.
     */
    private final boolean pointBlankBow;

    private int ticksSinceSwing = ATTACK_COOLDOWN_TICKS;
    // Starts EXPIRED: a target that has never been in reach earns no
    // hold memory - only an actual in-reach sighting opens the window.
    private int ticksSinceInReach = AIM_HOLD_TICKS + 1;
    private boolean pressLatched;
    private float lastYaw;
    private boolean drawing;
    private int chargeTicks;

    /**
     * Creates a combat behavior over one body position source. No
     * weapon ranking: the held item is never switched and the melee
     * band stays melee at every distance.
     *
     * @param name       stable identity for claims; never null or blank
     * @param positionSource body position accessor; never null
     */
    public CombatBehavior(String name, BodyPositionSource positionSource) {
        this(name, positionSource, WeaponCatalog.none(), false);
    }

    /**
     * Creates a combat behavior that holds the best hotbar weapon
     * while fighting and lets the bow answer at every distance when
     * no hotbar weapon outranks it - point-blank fire replaces bow
     * clubbing for a bow-only carrier, while a carried sword keeps
     * the melee band melee.
     *
     * @param name            stable identity for claims; never null or
     *                        blank
     * @param positionSource  body position accessor; never null
     * @param weapons         weapon ranking; never null
     */
    public CombatBehavior(String name, BodyPositionSource positionSource, WeaponCatalog weapons) {
        this(name, positionSource, weapons, true);
    }

    /**
     * Full wiring: ranking plus the point-blank policy flag.
     *
     * @param name            stable identity for claims; never null or
     *                        blank
     * @param positionSource  body position accessor; never null
     * @param weapons         weapon ranking; never null
     * @param pointBlankBow   whether the bow may answer inside the
     *                        melee band while nothing outranks it
     */
    private CombatBehavior(
            String name, BodyPositionSource positionSource, WeaponCatalog weapons, boolean pointBlankBow) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.weapons = Objects.requireNonNull(weapons, "weapons");
        this.pointBlankBow = pointBlankBow;
    }

    @Override
    public ExecutionReport tick(WorldView world, Directive directive, Actor actor) {
        if (directive == null || directive.overrides().combat() == null) {
            // A stale draw must not outlive its directive.
            abortDraw(actor);
            return ExecutionReport.running();
        }
        AimSolve aim = solveAim(world, directive);
        actor.submit(new Claim(Channel.ROT, 20, name, new Intent.Look(aim.yaw(), aim.pitch())));
        if (aim.ranging() || committedToShot(aim)) {
            return tickRanged(world, actor, aim.bowSlot());
        }
        return tickMelee(world, actor, aim);
    }

    /**
     * Whether an in-flight draw is past the commit threshold. The
     * range verdict may flip to melee mid-charge (a closing or edge-
     * jittering target); past {@link #BOW_COMMIT_TICKS} the shot is
     * finished, not aborted - the paid-in charge stays bounded at one
     * full draw and the release is under half a draw away, while
     * aborting would let a target oscillating across ATTACK_REACH
     * restart the draw every approach tick and never release at all.
     * The outer band edge is covered by the same guard: a committed
     * draw also survives the target stepping beyond BOW_RANGE for the
     * few ticks the release still needs.
     */
    @Feature(
            id = "combat.bow_draw.commit_threshold",
            face = "combat.bow_draw",
            description =
                    "Draw-commit threshold: a draw charged past 10 ticks (half full draw) is finished, not" +
                    " aborted, when the range verdict flips to melee. Prevents a target oscillating across" +
                    " ATTACK_REACH from restarting the draw every approach tick and never releasing.",
            vanillaRef = "No direct vanilla counterpart — player controls draw/release manually",
            deviation =
                    "Bot-specific stateful range routing: the commit guard bounds the paid-in charge at one full" +
                    " draw and keeps the remaining wait under half a draw.")
    private boolean committedToShot(AimSolve aim) {
        return drawing && chargeTicks >= BOW_COMMIT_TICKS && aim.bowSlot() >= 0;
    }

    /** One tick's aiming solution: target deltas, bearing, ballistics, weapon verdict. */
    // bowSlot is the carried loadout's slot (-1 absent), NOT the
    // range verdict - the commit guard needs the slot even on ticks
    // when the band gate answers melee.
    private record AimSolve(double dx, double dy, double dz, float yaw, float pitch, boolean ranging, int bowSlot) {

        double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * Computes the aiming solution: target-center deltas, the yaw
     * with its degeneracy guard, and the ballistic pitch. Aim
     * degeneracy guard: when standing on top of the target,
     * horizontal direction is noise and the computed yaw flips 180
     * degrees every tick - hold the last bearing instead. Arrow drop
     * raises the effective aim point on the ranged path; zero on the
     * melee path where the swing resolves without ballistics.
     */
    @Feature(
            id = "combat.bow_draw.arrow_drop_compensation",
            face = "combat.bow_draw",
            description =
                    "Arrow drop compensation: aim point lifted by 0.0028 * range² blocks (constant-velocity" +
                    " parabola fit: v=3 b/t, g=0.05 b/t² gives 0.5·g/v²). Honest to within a block across the" +
                    " 15-block bow band. Aim degeneracy guard holds last yaw when horizontal distance < 0.5" +
                    "(standing on target makes atan2 flip 180° per tick).",
            vanillaRef = "Arrow projectile physics (decompiled 1.20.1)",
            deviation =
                    "Bot-specific: the bot must pre-compensate drop because it cannot steer the arrow in flight;" +
                    "players aim by feel and correction.")
    private AimSolve solveAim(WorldView world, Directive directive) {
        Vec3 position = positionSource.get();
        CellPos aimCell = Goals.cellOf(directive.goal());
        double tx = aimCell.x() + 0.5;
        double ty = aimCell.y() + 1.0;
        double tz = aimCell.z() + 0.5;

        double dx = tx - position.x();
        double dy = ty - position.y();
        double dz = tz - position.z();

        double horizontal = Math.hypot(dx, dz);
        float yaw = lastYaw;
        if (horizontal >= AIM_MIN_HORIZONTAL) {
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            lastYaw = yaw;
        }

        double distSq = dx * dx + dy * dy + dz * dz;
        double distance = Math.sqrt(distSq);
        int bowSlot = rangedLoadoutSlot(world);
        // The band gate folds two policies. Beyond BOW_RANGE the bow
        // never answers. Inside ATTACK_REACH it answers only under the
        // weapon-aware configuration and only while nothing in the
        // hotbar outranks it - a carried sword keeps the melee band
        // melee, a bow-only carrier keeps firing point-blank.
        boolean ranging = bowSlot >= 0
                && distance <= BOW_RANGE
                && (distance > ATTACK_REACH || (pointBlankBow && !RangedLoadouts.meleeWeaponBeatsBow(world, weapons)));
        double lift = ranging ? ARROW_DROP_PER_BLOCK_SQUARED * distSq : 0.0;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy + lift, Math.max(horizontal, 0.001)));
        return new AimSolve(dx, dy, dz, yaw, pitch, ranging, bowSlot);
    }

    /**
     * The ranged path: switch to the bow, then pace the draw. Draw
     * pacing sustains Use(true) across the charge (a USE gap reads as
     * a release adapter-side), then one false tick fires
     * BowItem.releaseUsing with the accumulated charge.
     */
    @Feature(
            id = "combat.bow_draw.draw_and_release",
            face = "combat.bow_draw",
            description =
                    "Bow draw/release pacing: USE(true) held for 20 ticks (full charge), then one USE(false) tick" +
                    " triggers BowItem.releaseUsing. The bow owns the SLOT channel while ranging so holdBestWeapon" +
                    " cannot switch it off (bows carry no ATTACK_DAMAGE modifier). A melee press in progress is" +
                    " closed first (one USE edge per tick).",
            vanillaRef = "BowItem.releaseUsing + Player.use (decompiled 1.20.1)",
            deviation =
                    "Facade is not player-ticked, so the adapter pumps tickUseLoop every held tick; the falling" +
                    " edge or USE claim loss releases the bow to prevent an orphaned forever-charge.")
    private ExecutionReport tickRanged(WorldView world, Actor actor, int bowSlot) {
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

    /**
     * The melee path: drop any draw, hold the best weapon, and pace
     * the swing. Swing pacing holds the release until cooldown
     * expires so the adapter sees exactly one rising edge per attack
     * window. The reach memory lets the window survive brief
     * excursions past the gate instead of starving on edge jitter.
     */
    @Feature(
            id = "combat.melee.swing_pacing",
            face = "combat.melee",
            description =
                    "Melee swing pacing: USE(true) on cooldown expiry + in-reach, USE(false) the next tick —" +
                    " exactly one rising edge per attack window. Reach memory (AIM_HOLD_TICKS=3) lets the window" +
                    " survive brief excursions past the gate instead of starving on edge jitter. Fixed 10-tick" +
                    " behavior-layer pace; the resolver's actual cooldown may be longer (sword 12.5) and gates real" +
                    " damage.",
            vanillaRef = "Player attack cooldown (decompiled 1.20.1)",
            deviation =
                    "Behavior-layer pace (10 ticks) is decoupled from the resolver's item-derived cooldown — a" +
                    " fast pulse gets one landed hit per cooldown window, vanilla's effective output, not" +
                    " machine-gun damage.")
    private ExecutionReport tickMelee(WorldView world, Actor actor, AimSolve aim) {
        abortDraw(actor);

        holdBestWeapon(world, actor);

        boolean released = !pressLatched;
        ticksSinceSwing++;
        double reach = aim.distance();
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
     * The hotbar bow slot when a ranged loadout exists - delegated to
     * the shared combat-tier loadout reader (DefendProcess's refuse
     * gate reads the same source of truth). Distance gating is the
     * caller's: this answers loadout presence only.
     *
     * @param world read surface for the inventory; never null
     * @return the bow's hotbar slot, or -1 when the loadout is missing
     */
    private static int rangedLoadoutSlot(WorldView world) {
        return RangedLoadouts.hotbarBowSlot(world);
    }

    /**
     * Hold the hotbar's highest per-hit weapon while fighting: mob
     * melee has no attack-speed scaling (the strength ticker is
     * Player-only), so per-hit damage is the whole ranking - an axe
     * outranks a same-tier sword. One SLOT claim whenever the best
     * slot differs from the current selection; SelectSlot is recorded
     * body state, so an unchanged selection costs nothing per tick.
     */
    @Feature(
            id = "combat.melee.weapon_ranking",
            face = "combat.melee",
            description =
                    "Weapon ranking by per-hit damage only: mob melee has no attack-speed scaling (the strength" +
                    " ticker is Player-only), so per-hit damage is the whole ranking — an axe outranks a same-tier" +
                    " sword. Hoes net +0 and are filtered by the catalog. One SLOT claim whenever the best slot" +
                    " differs from current selection.",
            vanillaRef = "Player attack strength scaling (decompiled 1.20.1)",
            deviation =
                    "Bot has no Player attack-strength bar, so DPS ranking (damage * speed) is replaced by pure" +
                    " per-hit damage ranking. This is the root reason axes beat swords for the bot.")
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
}
