package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.Direction;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The place half of the entity binding: converts a one-shot INTERACT
 * claim (Intent.InteractBlock) into a block placement at the cell
 * adjacent to the clicked face. The engine applies every world mutation;
 * this class only validates and dispatches — boundary A holds because
 * nothing here runs without a winning claim from the actor's flush.
 *
 * <p>Contract: see boundaries.md decision 25 (INTERACT-channel
 * vocabulary) and issue 0007 Phase 1. Phase 1 scope is deliberately
 * narrow: only block placement from a BlockItem in the selected hotbar
 * slot, using the block's default state. Two deliberate deviations from
 * the vanilla ServerPlayerGameMode.useItemOn path, both because a
 * server-side bot has no Player until Phase 2's BotPlayerFacade:
 *
 * <ul>
 *   <li>The GameMode path is bypassed entirely — placement is a direct
 *       {@code level.setBlock} plus item shrink, gated on the same
 *       survival and entity-obstruction checks as
 *       {@code BlockItem.canPlace}. The Forge RightClickBlock hook and
 *       the BlockItem.place(BlockPlaceContext) state machine are
 *       skipped; protection-mod interop arrives with the facade.</li>
 *   <li>Only the default block state is placed into air. Direction-aware
 *       states need Block.getStateForPlacement(BlockPlaceContext), and
 *       replaceable cells (water, tall grass) need its canBeReplaced
 *       check — both lift with the Phase 2 facade.</li>
 * </ul>
 *
 * <p>Use-block interactions (open a chest, press a button, toggle a
 * lever) are NOT implemented here — they call
 * {@code BlockState.use(Level, Player, InteractionHand, BlockHitResult)}
 * which needs a Player. They are Phase 2 menu-system territory.
 *
 * <p>Reach gate: a claim outside bare-hand reach is ignored (placement
 * never happens), matching vanilla's mayInteract honesty without needing
 * its Player parameter. Same constant as DigExecutor.DIG_REACH_BLOCKS.
 *
 * <p>Implementation note: runs on the server tick thread only, called
 * from BindingActor.flush on the rising edge of an InteractBlock claim.
 */
// contract: see boundaries.md decision 25 (INTERACT-channel vocabulary;
//            all mutation behind the claim surface)
public final class InteractBlockExecutor {

    /**
     * Bare-hand block reach, eye to block center, in blocks. Same
     * constant as DigExecutor — a bot that can dig a block can place
     * against it.
     */
    public static final double PLACE_REACH_BLOCKS = 4.5;

    private final BotBodyEntity body;

    /**
     * Creates an executor bound to one body.
     *
     * @param body the physical carrier whose level is mutated and whose
     *             inventory supplies the placed block; never null
     */
    public InteractBlockExecutor(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * Attempt to place the selected hotbar block against the clicked face.
     * Silent no-op when any precondition fails (out of reach, selected
     * item is not a BlockItem, placement cell is not air, the block
     * cannot survive there, or an entity occupies the cell).
     *
     * @param claim the winning InteractBlock claim; never null
     */
    public void place(Intent.InteractBlock claim) {
        BlockPos target = new BlockPos(claim.target().x(),
            claim.target().y(), claim.target().z());
        if (!withinReach(target)) {
            return;
        }
        ItemStack held = body.getInventory().container()
            .getItem(body.selectedSlot);
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            // Selected item is not a placeable block — silent no-op,
            // same as a player right-clicking air with a sword.
            return;
        }
        BlockPos placePos = target.relative(
            toMinecraft(claim.face()));
        BlockState existing = body.level().getBlockState(placePos);
        // Phase 1: only place into air. Replaceable blocks (water, tall
        // grass) need a PlaceContext for the canBeReplaced check; that
        // context needs a Player. Lift with the Phase 2 facade.
        if (!existing.isAir()) {
            return;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        // Vanilla BlockItem.canPlace checks: the block must survive here
        // (no floating torches) and no entity may occupy the cell (no
        // self-entombment). CollisionContext.empty() is the player-less
        // shape vanilla itself uses when the placer is null.
        if (!state.canSurvive(body.level(), placePos)) {
            return;
        }
        if (!body.level().isUnobstructed(state, placePos,
                CollisionContext.empty())) {
            return;
        }
        // Flag 3 = UPDATE (1) | notify clients (2). Standard placement
        // flag set; the same value BlockItem.place uses after its own
        // checks.
        body.level().setBlock(placePos, state, 3);
        playPlaceSound(placePos, state);
        held.shrink(1);
        // Client players swing on right-click place; the server-side body
        // mirrors that visible cadence directly (same as DigExecutor.swing).
        body.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Whether the eye is within bare-hand reach of the block center.
     *
     * @param pos the clicked block; never null
     * @return true when the placement may proceed
     */
    private boolean withinReach(BlockPos pos) {
        Vec3 eye = body.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        return eye.distanceToSqr(center)
            <= PLACE_REACH_BLOCKS * PLACE_REACH_BLOCKS;
    }

    /**
     * Play the block's placement sound at the placement cell. Mirrors
     * the volume/pitch scaling BlockItem.place uses.
     *
     * @param pos   placement cell; never null
     * @param state placed block state; never null
     */
    private void playPlaceSound(BlockPos pos, BlockState state) {
        SoundType sound = state.getSoundType();
        body.level().playSound(null, pos, sound.getPlaceSound(),
            SoundSource.BLOCKS,
            (sound.getVolume() + 1.0f) / 2.0f,
            sound.getPitch() * 0.8f);
    }

    /**
     * Map the api-layer Direction to the engine Direction. Explicit
     * switch (not ordinal-based) so a future reordering of either enum
     * fails at compile time instead of silently swapping faces. The
     * engine type stays spelled out at this single seam because the
     * api-layer {@code Direction} import owns the short name.
     *
     * @param d api-layer direction; never null
     * @return the matching engine direction
     */
    private static net.minecraft.core.Direction toMinecraft(Direction d) {
        return switch (d) {
            case DOWN -> net.minecraft.core.Direction.DOWN;
            case UP -> net.minecraft.core.Direction.UP;
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case WEST -> net.minecraft.core.Direction.WEST;
            case EAST -> net.minecraft.core.Direction.EAST;
        };
    }
}
