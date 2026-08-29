package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.actor.DigPacing;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The dig half of the entity binding: converts a held INTERACT claim
 * (Intent.Dig) into vanilla destroy progress and, on completion, the
 * block break. The engine applies every world mutation; this class
 * only paces it - boundary A holds because nothing here runs without a
 * winning claim from the actor's flush.
 *
 * <p>Contract: see boundaries.md decision 25 (issue 0009). The state
 * machine mirrors ServerPlayerGameMode's destroy machinery from the
 * decompiled 1.20.1 tree: per-tick progress accumulates on an
 * unchanged target ({@code perTick * heldTicks}), crack stages go to
 * observers via {@code destroyBlockProgress}, and the break itself is
 * {@code level.destroyBlock(pos, true)} (drops + the 2001 level
 * event, both handled inside that call).
 *
 * <p>Deliberate deviations, inventoried (a server-side bot has no
 * Player until Phase 2's BotPlayerFacade):
 * <ul>
 *   <li>Vanilla's stop-destroy 0.7 completion shortcut (lag
 *       compensation for a client releasing the button a few packets
 *       early) is not mirrored — the bot's claims are ground truth,
 *       so the break lands at exactly 1.0.</li>
 *   <li>Forge LeftClickBlock / onBlockBreakEvent hooks are Player-typed
 *       — protection-mod interop defers with the facade.</li>
 *   <li>Tool durability and mining exhaustion ARE shipped: after the
 *       block breaks, {@code Item.mineBlock} damages the held tool
 *       (1 for pickaxes/axes/shovels, 2 for swords, 1 for shears,
 *       0 for non-tools), and {@code FoodData.addExhaustion(0.005F)}
 *       applies the vanilla per-block mining exhaustion (mirrors
 *       Block.playerDestroy). Unbreaking and Mending enchantments run
 *       inside hurtAndBreak automatically.</li>
 *   <li>match_tool loot fidelity and XP pop ARE shipped: the break
 *       goes through Block.dropResources with the held stack as the
 *       TOOL context, so shears-on-leaves, silk-touch, fortune, and
 *       the block's experience drop all resolve correctly.</li>
 * </ul>
 *
 * <p>Reach gate: a claim outside bare-hand reach is ignored (progress
 * never starts), matching vanilla's mayInteract honesty without
 * needing its Player parameter.
 *
 * <p>Implementation note: runs on the server tick thread only, called
 * from BindingActor.flush.
 */
// contract: see boundaries.md decision 25 (dig is INTERACT-channel
//            vocabulary; all mutation behind the claim surface)
public final class DigExecutor {

    private final BotBodyEntity body;

    /** Current dig site; null while no dig is in progress. */
    private BlockPos target;

    /** Ticks the current target has been held, 1-based. */
    private int heldTicks;

    /** Last crack stage broadcast, -1 when none. */
    private int lastStage = -1;

    /**
     * Creates an executor bound to one body.
     *
     * @param body the physical carrier whose level is dug; never null
     */
    public DigExecutor(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * One tick of a held dig claim.
     *
     * @param claimed the claimed cell; never null
     */
    public void dig(CellPos claimed) {
        BlockPos pos = new BlockPos(claimed.x(), claimed.y(), claimed.z());
        if (!pos.equals(target)) {
            // New site (or first tick): reset any previous progress.
            // Vanilla's ABORT path broadcasts the clear; so does this.
            resetBroadcast();
            target = pos;
            heldTicks = 0;
            lastStage = -1;
        }
        BlockState state = body.level().getBlockState(pos);
        if (state.isAir()) {
            // Somebody else finished it (a player, a piston): stop
            // silently, stay locked on the site in case more falls in.
            resetBroadcast();
            heldTicks = 0;
            lastStage = -1;
            return;
        }
        if (!ReachPolicy.withinReach(body.getEyePosition(), pos)) {
            // Out-of-reach claim: no progress, no broadcast - the
            // claim keeps arriving, the executor stays honest.
            return;
        }
        // Tool facts from the selected hotbar slot (issue 0007 Phase 1
        // tool supplier). Recomputed every tick because the SLOT channel
        // may switch the held item mid-dig; vanilla's per-tick
        // incremental destroy progress does the same.
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        float baseSpeed = held.getDestroySpeed(state);
        int efficiency = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, held);
        int hasteAmplifier = body.hasEffect(MobEffects.DIG_SPEED)
                ? body.getEffect(MobEffects.DIG_SPEED).getAmplifier()
                : -1;
        int fatigueAmplifier = body.hasEffect(MobEffects.DIG_SLOWDOWN)
                ? body.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()
                : -1;
        boolean underwaterWithoutAqua = body.isEyeInFluid(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(body);
        float toolSpeed = DigPacing.applyDigSpeedModifiers(
                baseSpeed, efficiency, hasteAmplifier, fatigueAmplifier, underwaterWithoutAqua);
        boolean hasCorrectTool = !state.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(state);
        heldTicks++;
        float progress = DigPacing.cumulativeProgress(
                DigPacing.perTickProgress(
                        state.getDestroySpeed(body.level(), pos), toolSpeed, hasCorrectTool, body.onGround()),
                heldTicks);
        if (progress >= 1.0f) {
            // Clear the crack broadcast first: the manual break sequence
            // emits the 2001 effect itself, and a stale stage would linger
            // on the next dig of the same cell.
            body.level().destroyBlockProgress(body.getId(), pos, -1);
            // Tool-aware break: Level.destroyBlock always passes
            // ItemStack.EMPTY as the tool to Block.dropResources, so
            // match_tool loot conditions (stone→pickaxe, leaves→shears,
            // silk-touch, fortune) never fired. Replicate the vanilla
            // dropResources path with the actual held stack: capture
            // state + block entity + fluid BEFORE removal, emit 2001
            // break particles (skip for FireBlock, same as destroyBlock),
            // replace with the fluid's legacy block (waterlogged stairs
            // keep their water), then drop resources with TOOL=held and
            // THIS_ENTITY=body. XP is handled inside dropResources via
            // spawnAfterBreak → ForgeHooks.dropXpForBlock (gated on the
            // harvest check, so wrong-tool breaks yield no XP).
            BlockState breakState = state;
            BlockEntity breakEntity = breakState.hasBlockEntity() ? body.level().getBlockEntity(pos) : null;
            FluidState breakFluid = body.level().getFluidState(pos);
            if (!(breakState.getBlock() instanceof net.minecraft.world.level.block.FireBlock)) {
                body.level().levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(breakState));
            }
            body.level().setBlock(pos, breakFluid.createLegacyBlock(), 3);
            net.minecraft.world.level.block.Block.dropResources(breakState, body.level(), pos, breakEntity, body, held);
            // Tool durability: Item.mineBlock damages the held stack
            // (1 for DiggerItem, 2 for SwordItem, 1 for ShearsItem, 0
            // for base Item). Gated on destroySpeed != 0 inside each
            // override, so insta-break blocks (torches, flowers) cost
            // nothing. Unbreaking/Mending run inside hurtAndBreak.
            held.getItem().mineBlock(held, body.level(), breakState, pos, body);
            // Mining exhaustion: vanilla Block.playerDestroy applies
            // 0.005F per block broken (the same value Player applies
            // via causeFoodExhaustion). The bot's FoodData is a custom
            // carrier field (HungerTicker ticks it), so add directly.
            body.getFoodData().addExhaustion(0.005F);
            target = null;
            heldTicks = 0;
            lastStage = -1;
            return;
        }
        int stage = DigPacing.stageOf(progress);
        if (stage != lastStage) {
            body.level().destroyBlockProgress(body.getId(), pos, stage);
            lastStage = stage;
        }
        // Client players swing every tick their held dig progresses;
        // the server-side body mirrors that visible cadence directly.
        body.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The claim stopped arriving (channel unclaimed or a different
     * intent won): cancel the in-progress dig and clear the crack
     * broadcast, exactly like vanilla's stop/abort paths.
     */
    public void release() {
        resetBroadcast();
        target = null;
        heldTicks = 0;
        lastStage = -1;
    }

    /** Clear the crack broadcast for the current site, if any. */
    private void resetBroadcast() {
        if (target != null && lastStage >= 0) {
            body.level().destroyBlockProgress(body.getId(), target, -1);
        }
    }
}
