package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.Tame;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.tame.TameFoodCatalog;
import javax.annotation.Nullable;

/**
 * Taming micro-execution: hold the species' tame item and pace the
 * use-on-entity presses while the directive's goal walks the body
 * alongside the animal. Deliberately owns NO locomotion, NO aim, and
 * NO world scan - the order carries the species key (the process
 * only emits it after a verified sighting), and the executor
 * resolves the target by id and gates reach. Mirrors the fish
 * behavior's ownership split: this tier adds SLOT equip plus the
 * INTERACT press edges, nothing else. Out-of-reach presses are
 * absorbed by the executor's reach gate (a no-op, no item lost), so
 * the cadence simply keeps pressing as the approach closes.
 *
 * <p>The press cadence doubles as the sit-toggle guard: a successful
 * tame press consumes the held item, and the interval between
 * presses is far longer than the pipeline's process-before-behavior
 * tick order, so the tamed directive is retired before the next
 * press could land empty-handed on the now-owned pet - an empty-hand
 * press by the owner toggles sit (vanilla TamableAnimal
 * interactions), and the tame task must never silently sit the
 * animal it just tamed.
 *
 * <p>Contract: see boundaries.md decision 11 (behavior owns
 * micro-execution). Runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (behavior owns micro-execution)
public final class TameBehavior implements Behavior {

    /**
     * Ticks between use-on-entity presses. Vanilla taming has no
     * cooldown on the interaction itself, so this is a pacing policy,
     * not an engine fact: fast enough that a 1/10 parrot lands inside
     * a default mission budget, slow enough that each attempt is a
     * distinct deliberate press.
     *
     * <p><b>Invariant:</b> this value must stay greater than 1. On the
     * tick the process detects the tamed flip and succeeds, it returns
     * its last directive (which still carries the tame order) for the
     * arbiter's one-tick retirement-lap corpse. The behavior receives
     * that stale directive on the same tick. A cooldown of N set by
     * the previous press means cooldownTicks = N-1 on the detection
     * tick, so no second press fires. If N were 1, cooldownTicks would
     * be 0 on the detection tick and the behavior would press again
     * with a now-empty hand (or a bone on a full-health tamed wolf),
     * triggering the sit toggle and standing the freshly-tamed animal
     * back up. Lowering this constant breaks the sit-toggle guard.
     */
    public static final int PRESS_INTERVAL_TICKS = 12;

    private final String name;

    private int cooldownTicks;

    /**
     * Creates the tame behavior.
     *
     * @param name stable identity for claims; never null or blank
     */
    public TameBehavior(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    @Override
    public ExecutionReport tick(WorldView world, @Nullable Directive directive, Actor actor) {
        Tame tame = directive != null ? directive.overrides().tame() : null;
        if (tame == null) {
            cooldownTicks = 0;
            return ExecutionReport.running();
        }

        InventoryView inventory = world.getInventory();
        int slot = TameFoodCatalog.hotbarTameItemSlot(inventory, tame.typeKey());
        if (slot < 0) {
            // The process owns the NO_TAME_ITEM verdict at first
            // sight; here the quiet skip just avoids pressing while
            // the item is spent.
            return ExecutionReport.running();
        }
        if (inventory.selectedSlot() != slot) {
            actor.submit(new Claim(Channel.SLOT, 20, name, new Intent.SelectSlot(slot)));
            return ExecutionReport.running();
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return ExecutionReport.running();
        }
        submitPress(actor, tame);
        cooldownTicks = PRESS_INTERVAL_TICKS;
        return ExecutionReport.running();
    }

    /**
     * One use-on-entity press. The claim lives a single tick - the
     * adapter's rising-edge gate turns the next claim-free tick into
     * the release, exactly the one-shot shape {@code InteractBlock}
     * uses for block clicks.
     *
     * @param actor claim surface; never null
     * @param tame  the active taming order; never null
     */
    @Feature(
            id = "taming.chain.press_pacing",
            face = "taming.chain",
            description = "Tame press pacing: equip the species' tame item via a SLOT claim, then one"
                    + " INTERACT-channel use-on-entity press every {@code PRESS_INTERVAL_TICKS} ticks while the"
                    + " goal keeps the body in reach. The cadence doubles as the sit-toggle guard - the tamed"
                    + " directive is retired between presses, so no empty-hand press ever lands on the freshly"
                    + " owned pet.",
            vanillaRef = "Player.interactOn -> Mob.interact -> TamableAnimal (decompiled 1.20.1)")
    private void submitPress(Actor actor, Tame tame) {
        actor.submit(new Claim(Channel.INTERACT, 20, name, new Intent.InteractEntity(tame.targetId())));
    }
}
