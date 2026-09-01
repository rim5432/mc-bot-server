package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.Fish;
import com.mcbot.mcbotserver.api.world.BobberSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Fishing micro-execution (issue 0010 section 7): hold the rod, cast
 * toward the ordered water, watch the bobber, and reel on the bite.
 * Deliberately owns NO locomotion - the directive's goal walks the
 * body to the water's edge; this behavior only adds SLOT equip and
 * the USE cast/reel edges on top, mirroring how combat adds ROT/USE.
 *
 * <p>Bite sensing is the dip, not the nibble: vanilla keeps the
 * nibble state private (reflection stays behind the test door,
 * H-R1), and the only outside-visible bite signal is the bobber's
 * sharp vertical drop. The behavior arms itself after the cast flight
 * settles, then a drop of {@link #DIP_THRESHOLD} blocks or more
 * between consecutive ticks means a fish - reel once and the rod's
 * own retrieve logic lands the food in the backpack, where the
 * process's per-tick food scan closes the mission.
 *
 * <p>Contract: see boundaries.md decision 11 (behavior owns
 * micro-execution) and issue 0010 section 4.3/4.5. Runs on the server
 * tick thread only.
 */
// contract: see boundaries.md decision 11 (behavior owns micro-execution)
public final class FishBehavior implements Behavior {

    /**
     * Ticks of no bite before the behavior stops fishing (0010 section
     * 4.5 estimated 200; a real vanilla bite averages 5-30s, so the
     * budget rides 600 and gets revisited at engine verification).
     */
    public static final int BITE_BUDGET_TICKS = 600;

    /**
     * Vertical drop between consecutive ticks that counts as a bite.
     * The bite kick moves the bobber ~0.4 blocks in one tick; natural
     * water bob stays within ~0.05.
     */
    public static final double DIP_THRESHOLD = 0.25;

    /** Bobber search radius around the ordered water cell (blocks). */
    public static final double BOBBER_RADIUS = 24.0;

    /** Cast-flight ticks ignored before settle counting starts. */
    private static final int CAST_GRACE_TICKS = 30;

    /** Consecutive stable ticks that mark the bobber as landed. */
    private static final int SETTLE_TICKS = 5;

    private static final String ROD_ID = "minecraft:fishing_rod";

    private final String name;

    private boolean pressPending;
    private int castAge;
    private int settleTicks;

    @Nullable
    private Double lastBobberY;

    private int noBiteTicks;
    private boolean budgetSpent;
    private boolean armed;

    /**
     * Creates the fish behavior. The ordered water cell (inside the
     * directive's fish order) centers every bobber scan, so no body
     * position accessor is needed.
     *
     * @param name stable identity for claims; never null or blank
     */
    public FishBehavior(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    @Override
    public ExecutionReport tick(WorldView world, @Nullable Directive directive, Actor actor) {
        Fish fish = directive != null ? directive.overrides().fish() : null;
        if (fish == null) {
            resetTransients();
            return ExecutionReport.running();
        }

        InventoryView inventory = world.getInventory();
        int rod = hotbarRod(inventory);
        if (rod < 0) {
            return ExecutionReport.running();
        }
        if (inventory.selectedSlot() != rod) {
            actor.submit(new Claim(Channel.SLOT, 20, name, new Intent.SelectSlot(rod)));
            return ExecutionReport.running();
        }

        List<BobberSnapshot> bobbers = world.getBobbers(fish.waterCell(), BOBBER_RADIUS, ViewMode.LIVE);
        BobberSnapshot bobber = bobbers.isEmpty() ? null : bobbers.get(0);

        if (bobber == null || pressPending) {
            return tickCast(actor, bobber == null);
        }
        if (bobber.hookedEntity()) {
            // Snagged a mob or item: reel to recover the rod, the fish
            // watch restarts with the next cast.
            reel(actor);
            resetWatch();
            return ExecutionReport.running();
        }
        if (!armed) {
            return tickSettle(bobber.pos().y());
        }
        return tickBiteWatch(actor, bobber.pos().y());
    }

    /**
     * Cast edges: with no bobber out, one rising press released the
     * next tick (the rod's cast is a click, not a hold); with a
     * bobber out but a press still pending, land the release half
     * first.
     *
     * @param actor  claim surface; never null
     * @param noBobber true when the water holds no bobber this tick
     * @return the tick report; never null
     */
    private ExecutionReport tickCast(Actor actor, boolean noBobber) {
        if (noBobber) {
            if (!pressPending) {
                actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(true)));
                pressPending = true;
                castAge = 0;
                settleTicks = 0;
                lastBobberY = null;
                armed = false;
            } else {
                actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
                pressPending = false;
            }
            return ExecutionReport.running();
        }
        actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
        pressPending = false;
        return ExecutionReport.running();
    }

    /**
     * Bobber out, settling phase: after the cast grace, watch the
     * float come to rest - SETTLE_TICKS of stillness arms the bite
     * watch.
     *
     * @param bobberY the bobber's current y
     * @return the tick report; never null
     */
    private ExecutionReport tickSettle(double bobberY) {
        castAge++;
        if (castAge > CAST_GRACE_TICKS) {
            if (lastBobberY != null && Math.abs(lastBobberY - bobberY) <= 0.05) {
                settleTicks++;
            } else {
                settleTicks = 0;
            }
            if (settleTicks >= SETTLE_TICKS) {
                armed = true;
                noBiteTicks = 0;
            }
        }
        lastBobberY = bobberY;
        return ExecutionReport.running();
    }

    /**
     * Armed bite watch: a dip past the threshold is the bite (one
     * reel, then the watch restarts and the food scan takes over);
     * a spent bite budget reels in and stops - the mission's own
     * timeout then owns the verdict (0010 section 4.5).
     *
     * @param actor  claim surface; never null
     * @param bobberY the bobber's current y
     * @return the tick report; never null
     */
    private ExecutionReport tickBiteWatch(Actor actor, double bobberY) {
        if (noBiteTicks > BITE_BUDGET_TICKS) {
            if (!budgetSpent) {
                reel(actor);
                budgetSpent = true;
            }
            return ExecutionReport.running();
        }
        noBiteTicks++;
        if (lastBobberY != null && lastBobberY - bobberY >= DIP_THRESHOLD) {
            reel(actor);
            resetWatch();
            return ExecutionReport.running();
        }
        lastBobberY = bobberY;
        return ExecutionReport.running();
    }

    private void reel(Actor actor) {
        if (!pressPending) {
            actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(true)));
            pressPending = true;
        } else {
            actor.submit(new Claim(Channel.USE, 20, name, new Intent.Use(false)));
            pressPending = false;
        }
    }

    private void resetWatch() {
        castAge = 0;
        settleTicks = 0;
        lastBobberY = null;
        armed = false;
        noBiteTicks = 0;
    }

    private void resetTransients() {
        resetWatch();
        pressPending = false;
        budgetSpent = false;
    }

    /**
     * First fishing rod in the hotbar.
     *
     * @param inventory the live inventory snapshot; never null
     * @return the rod's hotbar slot, or -1 when none is equipped
     */
    private static int hotbarRod(InventoryView inventory) {
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            var item = inventory.main().get(slot);
            if (!item.isEmpty() && ROD_ID.equals(item.itemId())) {
                return slot;
            }
        }
        return -1;
    }
}
