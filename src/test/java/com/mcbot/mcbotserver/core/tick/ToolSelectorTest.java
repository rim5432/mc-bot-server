package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.actor.ToolCatalog;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for mission dig tool selection: while a dig-family
 * mission drives, the selector holds the hotbar slot that digs the
 * target strictly faster than the held item. Deliberately different
 * from combat's tie rule: the held slot wins equal-speed ties, so
 * the selection never churns for zero gain.
 */
class ToolSelectorTest {

    private static final CellPos TARGET = new CellPos(3, 63, 3);

    private static final String STONE = "minecraft:stone";

    private static final String IRON_PICK = "minecraft:iron_pickaxe";

    private static final String STONE_PICK = "minecraft:stone_pickaxe";

    /** Iron 6 beats stone 4 per the vanilla tier table; all other pairs are baseline. */
    private static final ToolCatalog CATALOG = (itemId, blockId) -> STONE.equals(blockId) && IRON_PICK.equals(itemId)
            ? 6f
            : STONE.equals(blockId) && STONE_PICK.equals(itemId) ? 4f : 1f;

    @Test
    void holdsFastestToolForTargetBlock() {
        RecordingActor actor = new RecordingActor();
        MockWorldView world = stoneTarget();
        world.setInventory(inventory(0, null, null, IRON_PICK, null, STONE_PICK));

        selector().select(world, actor, 5, "mission:dig:t1", TARGET);

        assertEquals(2, selectedSlotOf(actor), "iron (6) must beat stone (4)");
    }

    @Test
    void heldSlotWinsEqualSpeedTies() {
        RecordingActor actor = new RecordingActor();
        MockWorldView world = stoneTarget();
        world.setInventory(inventory(2, null, null, IRON_PICK, null, null, IRON_PICK));

        selector().select(world, actor, 5, "mission:dig:t1", TARGET);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "an equal-speed tool elsewhere must not churn the selection");
    }

    @Test
    void alreadyHoldingBestClaimsNothing() {
        RecordingActor actor = new RecordingActor();
        MockWorldView world = stoneTarget();
        world.setInventory(inventory(2, null, STONE_PICK, IRON_PICK));

        selector().select(world, actor, 5, "mission:dig:t1", TARGET);

        assertTrue(actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT));
    }

    @Test
    void wrongToolsNeverClaim() {
        RecordingActor actor = new RecordingActor();
        MockWorldView world = stoneTarget();
        world.setInventory(inventory(0, "minecraft:dirt", "minecraft:cooked_beef"));

        selector().select(world, actor, 5, "mission:dig:t1", TARGET);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "baseline-speed items must not mint a switch");
    }

    @Test
    void firstFastestSlotWinsWhenHandEmpty() {
        RecordingActor actor = new RecordingActor();
        MockWorldView world = stoneTarget();
        world.setInventory(inventory(0, null, STONE_PICK, null, null, STONE_PICK));

        selector().select(world, actor, 5, "mission:dig:t1", TARGET);

        assertEquals(1, selectedSlotOf(actor), "equal speeds keep the lower hotbar slot");
    }

    @Test
    void airTargetSubmitsNothing() {
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, null, null, IRON_PICK));

        selector().select(world, actor, 5, "mission:dig:t1", TARGET);

        assertTrue(actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT));
    }

    private static ToolSelector selector() {
        return new ToolSelector(CATALOG);
    }

    private static MockWorldView stoneTarget() {
        return new MockWorldView().putBlock(new BlockSnapshot(TARGET, STONE));
    }

    private static int selectedSlotOf(RecordingActor actor) {
        return actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .map(c -> (Intent.SelectSlot) c.intent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a SLOT claim"))
                .slot();
    }

    /**
     * An own-inventory snapshot with the given hotbar contents: a null
     * entry (or past the array) means an empty slot.
     *
     * @param selected  current hotbar selection 0..8
     * @param hotbarIds item ids for hotbar slots 0..8 in order; null
     *                  leaves the slot empty
     * @return the inventory view; never null
     */
    private static InventoryView inventory(int selected, String... hotbarIds) {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        for (int i = 0; i < Math.min(hotbarIds.length, InventoryView.HOTBAR_SIZE); i++) {
            if (hotbarIds[i] != null) {
                main.set(i, new ItemView(hotbarIds[i], 1));
            }
        }
        return new InventoryView(
                main,
                selected,
                List.copyOf(Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)),
                ItemView.EMPTY);
    }
}
