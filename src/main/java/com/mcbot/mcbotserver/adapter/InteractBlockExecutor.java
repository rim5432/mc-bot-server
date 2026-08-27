package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The right-click half of the entity binding: converts a one-shot
 * INTERACT claim ({@link Intent.InteractBlock}) into the vanilla use
 * chain at the clicked cell - block interaction first
 * ({@code BlockState.use}), then item use-on ({@code ItemStack.useOn},
 * BlockItem placement included) when the block interaction passed.
 * The engine applies every world mutation; this class only validates
 * and dispatches - boundary A holds because nothing here runs without
 * a winning claim from the actor's flush.
 *
 * <p>Contract: see boundaries.md decision 25 (INTERACT-channel
 * vocabulary) and issue 0007 Phase 2. Dispatch order is vanilla's
 * non-sneak right-click order - the bot never sneaks, so the
 * sneak-bypass branch is unreachable by construction, and a
 * right-click on a door opens it even while holding a block.
 * Direction-aware placement (stairs facing, snow layers, waterlog)
 * and replaceable target cells ride {@code BlockItem.place}'s own
 * state machine, which retires the Phase 1 default-state debt.
 *
 * <p>Deliberate deviations from {@code ServerPlayerGameMode.useItemOn},
 * both because the carrier is a PathfinderMob and the acting surface
 * a {@link BotPlayerFacade} rather than a ServerPlayer:
 *
 * <ul>
 *   <li>The Forge RightClickBlock hook is not fired - protection-mod
 *       interop and event cancellations are not consulted.</li>
 *   <li>Blocks that expose a container UI ({@code MenuProvider}) are
 *       skipped: the device-side container experience lives in the
 *       menu verbs (issue 0007 Phase 2), and a second open path would
 *       fight the MenuOpener's transaction state.</li>
 * </ul>
 *
 * <p>Reach gate: a claim outside bare-hand reach is ignored, matching
 * vanilla's mayInteract honesty without needing its Player parameter.
 * Reach policy: see {@link ReachPolicy}.
 *
 * <p>Implementation note: runs on the server tick thread only, called
 * from BindingActor.flush on the rising edge of an InteractBlock claim.
 */
// contract: see boundaries.md decision 25 (INTERACT-channel vocabulary;
//            all mutation behind the claim surface)
public final class InteractBlockExecutor {

    private final BotBodyEntity body;

    private final BotPlayerFacade facade;

    /**
     * Creates an executor bound to one body acting through one facade.
     *
     * @param body   the physical carrier whose level is mutated and
     *               whose inventory supplies the held item; never null
     * @param facade the Player-typed acting surface for the vanilla use
     *               chain; never null
     */
    public InteractBlockExecutor(BotBodyEntity body, BotPlayerFacade facade) {
        this.body = body;
        this.facade = facade;
    }

    /**
     * Run the use chain and discard the verdict - the shape the
     * synchronous place verb uses, which closes its own silent-no-op
     * gap with a post-state read.
     *
     * @param claim the winning InteractBlock claim; never null
     */
    public void place(Intent.InteractBlock claim) {
        chain(claim);
    }

    /**
     * Run the use chain and report whether any action consumed it.
     *
     * @param claim the winning InteractBlock claim; never null
     * @return true when block interaction or item use-on consumed the
     *         click; false on every silent no-op
     */
    public boolean use(Intent.InteractBlock claim) {
        return chain(claim);
    }

    /**
     * The vanilla non-sneak right-click chain: reach gate, block
     * interaction, then item use-on against the same hit.
     *
     * @param claim the winning InteractBlock claim; never null
     * @return true when an action consumed the click
     */
    private boolean chain(Intent.InteractBlock claim) {
        BlockPos target = new BlockPos(
                claim.target().x(), claim.target().y(), claim.target().z());
        if (!ReachPolicy.withinReach(body.getEyePosition(), target)) {
            return false;
        }
        // BlockItem.place derives placement orientation from the
        // placer's position and look angles - mirror the body first.
        facade.syncPosition();
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        BlockHitResult hit = new BlockHitResult(
                new net.minecraft.world.phys.Vec3(
                        claim.hitPos().x(), claim.hitPos().y(), claim.hitPos().z()),
                toMinecraft(claim.face()),
                target,
                false);
        BlockState clicked = body.level().getBlockState(target);
        if (!clicked.isAir()) {
            if (clicked.getMenuProvider(body.level(), target) != null) {
                // Container UI belongs to the menu verbs; see the class
                // Javadoc deviation list.
                return false;
            }
            InteractionResult use = clicked.use(body.level(), facade, InteractionHand.MAIN_HAND, hit);
            if (use.consumesAction()) {
                // Client players swing on a consumed right-click; the
                // server-side body mirrors that visible cadence directly
                // (same as DigExecutor.swing).
                body.swing(InteractionHand.MAIN_HAND);
                return true;
            }
        }
        if (held.isEmpty()) {
            return false;
        }
        InteractionResult itemUse = held.useOn(new UseOnContext(facade, InteractionHand.MAIN_HAND, hit));
        if (itemUse.consumesAction()) {
            body.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
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
