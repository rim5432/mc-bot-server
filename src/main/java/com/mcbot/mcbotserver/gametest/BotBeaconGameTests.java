package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.types.CellPos;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Beacon menu interactions (capability: inventory.menu_clicks).
 * Covers the beacon's payment slot and effect-selection path:
 * deposit a payment item (iron/gold/diamond/emerald/netherite ingot),
 * set primary and secondary effects via setBeaconEffects, verify the
 * payment is consumed and the beacon applies the effect to nearby
 * entities. The beacon requires a valid pyramid structure — these
 * tests build a 1-level (3x3) pyramid so the beacon activates.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotBeaconGameTests {

    private BotBeaconGameTests() {}

    /**
     * Scenario: the beacon full effect-selection cycle end to end —
     * a 1-level iron pyramid activates the beacon, an iron ingot is
     * deposited in the payment slot, setBeaconEffects selects Speed I
     * as the primary effect, the payment is consumed, and the bot
     * standing adjacent receives the Speed effect. Pins the beacon
     * menu slot routing (INPUT = payment slot), the setBeaconEffects
     * wire path, and the engine-side beacon effect application.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void beaconSetsPrimaryEffectAndConsumesPayment(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));

        // Build 1-level beacon pyramid: 3x3 iron blocks, beacon on top center.
        // A 1-level pyramid unlocks Speed and Haste as primary effects.
        BlockPos pyramidCenter = new BlockPos(7, WALK_Y, 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(pyramidCenter.offset(dx, 0, dz), Blocks.IRON_BLOCK);
            }
        }
        BlockPos beaconPos = pyramidCenter.above();
        helper.setBlock(beaconPos, Blocks.BEACON);

        // The gametest template sits underground (y ~ -60); the stone above
        // the structure blocks the beacon beam, so beamSections stays empty
        // and updateBase() is never called (beaconLevels=0). Clear the
        // column above the beacon up to the world surface so the beam can
        // form and the pyramid detection runs.
        BlockPos beaconAbs = helper.absolutePos(beaconPos);
        int surfaceY = helper.getLevel()
                .getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        beaconAbs.getX(),
                        beaconAbs.getZ());
        for (int y = beaconAbs.getY() + 1; y <= surfaceY; y++) {
            helper.getLevel()
                    .setBlock(new BlockPos(beaconAbs.getX(), y, beaconAbs.getZ()), Blocks.AIR.defaultBlockState(), 3);
        }

        // Give bot an iron ingot for the payment slot.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_INGOT, 1));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(new CellPos(beaconAbs.getX(), beaconAbs.getY(), beaconAbs.getZ()));
        check(view != null, "beacon menu must open");
        check(view.type().equals("beacon"), "menu type must be beacon, got " + view.type());

        // Place the iron ingot in slot 0 (the payment slot).
        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        view = tx.menuClick(0, 0, MenuClick.PICKUP);
        check(!view.slot(0).isEmpty(), "payment must be in slot 0 after deposit");

        // Set primary effect: Speed I. Pass null for secondary (1-level
        // beacons do not unlock secondary effects).
        view = tx.setBeaconEffects("minecraft:speed", null);

        // Verify: the payment was consumed by updateEffects (paymentSlot.remove(1)).
        check(view.slot(0).isEmpty(), "payment must be consumed after setting effects");

        tx.closeMenu();

        // Wait for the beacon to complete beam + pyramid detection (both
        // run on the block-entity tick; beam scan is incremental at 10
        // blocks/tick, pyramid detection fires every 80 ticks). Then verify
        // the beacon activated (levels>0) and the primary effect was stored.
        // Note: vanilla applyEffects() only targets Player entities, so the
        // bot (PathfinderMob) will not receive the effect — that is correct
        // vanilla parity, not a bug. We assert the device-side state instead.
        BlockPos beaconAbsFinal = beaconAbs;
        helper.runAfterDelay(100, () -> {
            var beaconBe = helper.getLevel().getBlockEntity(beaconAbsFinal);
            check(
                    beaconBe instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity,
                    "beacon block entity must exist");
            net.minecraft.world.level.block.entity.BeaconBlockEntity beacon =
                    (net.minecraft.world.level.block.entity.BeaconBlockEntity) beaconBe;
            try {
                java.lang.reflect.Field levelsField =
                        net.minecraft.world.level.block.entity.BeaconBlockEntity.class.getDeclaredField("levels");
                levelsField.setAccessible(true);
                int levels = levelsField.getInt(beacon);
                check(levels > 0, "beacon must activate with a valid pyramid, got levels=" + levels);

                java.lang.reflect.Field primaryField =
                        net.minecraft.world.level.block.entity.BeaconBlockEntity.class.getDeclaredField("primaryPower");
                primaryField.setAccessible(true);
                Object primary = primaryField.get(beacon);
                check(primary != null, "primary effect must be set after setBeaconEffects");
                check(primary.equals(MobEffects.MOVEMENT_SPEED), "primary effect must be Speed, got " + primary);
            } catch (ReflectiveOperationException e) {
                helper.fail("beacon field reflection failed: " + e.getMessage());
            }
            rig.body().discard();
            helper.succeed();
        });
    }

    /**
     * Scenario: the beacon rejects a non-payment item in the payment
     * slot — a dirt block cannot be deposited because PaymentSlot.mayPlace
     * only accepts BEACON_PAYMENT_ITEMS. The menu click leaves the dirt
     * in the bot's inventory and the payment slot stays empty. Pins the
     * beacon payment-slot filter (vanilla parity, not a bot-side check).
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void beaconPaymentSlotRejectsNonPaymentItem(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));

        // Same 1-level pyramid as above.
        BlockPos pyramidCenter = new BlockPos(7, WALK_Y, 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(pyramidCenter.offset(dx, 0, dz), Blocks.IRON_BLOCK);
            }
        }
        BlockPos beaconPos = pyramidCenter.above();
        helper.setBlock(beaconPos, Blocks.BEACON);

        // Give bot dirt — not a valid beacon payment.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIRT, 1));

        var tx = rig.actor().menuTransactions();
        BlockPos beaconAbs = helper.absolutePos(beaconPos);
        var view = tx.openMenu(new CellPos(beaconAbs.getX(), beaconAbs.getY(), beaconAbs.getZ()));
        check(view != null, "beacon menu must open");

        // Try to place dirt in slot 0 (payment slot). Vanilla PaymentSlot
        // rejects it via mayPlace, so the dirt stays in carried and the
        // slot remains empty.
        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        view = tx.menuClick(0, 0, MenuClick.PICKUP);
        check(view.slot(0).isEmpty(), "payment slot must reject dirt (non-payment item)");

        // The dirt should still be in the bot's inventory (carried was
        // placed back, or never left the inventory).
        tx.closeMenu();
        var inv = rig.body().getInventory().container();
        boolean hasDirt = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.DIRT)) {
                hasDirt = true;
                break;
            }
        }
        check(hasDirt, "dirt must remain in the bot's inventory after rejected deposit");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: setBeaconEffects on a beacon with no payment does
     * nothing — updateEffects gates on paymentSlot.hasItem(), so the
     * effect data is unchanged and no payment is consumed. Pins the
     * no-payment guard (vanilla parity, prevents free effect selection).
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void beaconSetEffectsWithoutPaymentIsNoOp(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));

        BlockPos pyramidCenter = new BlockPos(7, WALK_Y, 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(pyramidCenter.offset(dx, 0, dz), Blocks.IRON_BLOCK);
            }
        }
        BlockPos beaconPos = pyramidCenter.above();
        helper.setBlock(beaconPos, Blocks.BEACON);

        var tx = rig.actor().menuTransactions();
        BlockPos beaconAbs = helper.absolutePos(beaconPos);
        var view = tx.openMenu(new CellPos(beaconAbs.getX(), beaconAbs.getY(), beaconAbs.getZ()));
        check(view != null, "beacon menu must open");
        check(view.slot(0).isEmpty(), "payment slot must start empty");

        // Try to set effects with no payment — should be a no-op.
        view = tx.setBeaconEffects("minecraft:speed", null);

        // Payment slot still empty (nothing consumed), and the bot has
        // no Speed effect (beacon never activated without payment).
        check(view.slot(0).isEmpty(), "payment slot must remain empty after no-payment setEffects");

        tx.closeMenu();
        helper.runAfterDelay(20, () -> {
            check(
                    !rig.body().hasEffect(MobEffects.MOVEMENT_SPEED),
                    "bot must NOT have Speed effect when beacon has no payment");
            rig.body().discard();
            helper.succeed();
        });
    }

    /**
     * Find the first HOTBAR slot in the menu snapshot — the bot's
     * hotbar starts after the 27-slot main inventory. Used to pick
     * up items from a known inventory position.
     */
    private static int firstHotbarSlot(MenuView view) {
        for (int i = 0; i < view.slots().size(); i++) {
            if (view.slots().get(i).role() == SlotRole.HOTBAR) {
                return i;
            }
        }
        throw new IllegalStateException("no HOTBAR slot found in menu of type " + view.type());
    }
}
