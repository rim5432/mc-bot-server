package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

/**
 * The use-on-entity half of the entity binding: converts a one-shot
 * INTERACT claim ({@link com.mcbot.mcbotserver.api.actor.Intent.InteractEntity})
 * into the vanilla entity interaction chain - {@code
 * Player.interactOn}, which routes {@code Mob.interact} (leash,
 * saddle, tame feeds) and {@code ItemStack.interactLivingEntity}.
 * The engine applies every mutation, including the tame-item
 * consumption inside {@code Wolf.mobInteract}; this class only
 * resolves, gates, and dispatches - boundary A holds because nothing
 * here runs without a winning claim from the actor's flush.
 *
 * <p>Deliberate deviation from the vanilla client press: a player
 * presses what the crosshair names, the device resolves the claim's
 * entity id against the server level and gates reach - the
 * mechanical equivalent, and the same id-resolution shape the melee
 * path replaced with a cone. Reach uses the melee surface radius:
 * vanilla's entity-interaction envelope.
 *
 * <p>Contract: see boundaries.md decision 25 (INTERACT-channel
 * vocabulary; all mutation behind the claim surface).
 *
 * <p>Implementation note: runs on the server tick thread only, called
 * from BindingActor.flush on the rising edge of an InteractEntity
 * claim.
 */
// contract: see boundaries.md decision 25 (INTERACT-channel vocabulary;
//            all mutation behind the claim surface)
public final class InteractEntityExecutor {

    /**
     * Entity-interaction reach in blocks, matching the melee surface
     * radius: the shared envelope every in-person interaction rides.
     */
    public static final double ENTITY_REACH_BLOCKS = 3.0;

    private final BotBodyEntity body;

    private final BotPlayerFacade facade;

    /**
     * Creates an executor bound to one body acting through one
     * facade.
     *
     * @param body   the physical carrier whose level resolves the
     *               target and whose inventory supplies the held
     *               item; never null
     * @param facade the Player-typed acting surface for the vanilla
     *               interaction chain; never null
     */
    public InteractEntityExecutor(BotBodyEntity body, BotPlayerFacade facade) {
        this.body = body;
        this.facade = facade;
    }

    /**
     * Run the use-on-entity chain and report whether it consumed the
     * press. A missing, removed, or out-of-reach target is a silent
     * no-op - the behavior tier's cadence simply presses again, and
     * no held item is touched on any no-op path.
     *
     * @param entityId the target entity's UUID string; never null
     * @return true when the interaction consumed the click
     */
    public boolean use(String entityId) {
        Entity target = resolve(entityId);
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        if (body.distanceToSqr(target) > ENTITY_REACH_BLOCKS * ENTITY_REACH_BLOCKS) {
            return false;
        }
        // Interaction outcomes can read the actor's position and look
        // angles - mirror the body first, same as the block chain.
        facade.syncPosition();
        InteractionResult result = facade.interactOn(target, InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            // Client players swing on a consumed right-click; the
            // server-side body mirrors that visible cadence directly
            // (same as the block use chain).
            body.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    /**
     * Resolve the claim's id against the body's server level.
     *
     * @param entityId the target entity's UUID string; never null
     * @return the entity, or null when the id is malformed or unknown
     *         to the level
     */
    private Entity resolve(String entityId) {
        UUID id;
        try {
            id = UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return ((ServerLevel) body.level()).getEntity(id);
    }
}
