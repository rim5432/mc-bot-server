package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * The directed-strike half of the entity binding: converts a one-shot
 * USE claim ({@link com.mcbot.mcbotserver.api.actor.Intent.Strike})
 * into one melee hit on the ADDRESSED entity - the hurt-side sibling
 * of {@link InteractEntityExecutor}, riding the same id-resolution
 * shape. The damage math is the resolver's full crit-aware chain
 * ({@link MeleeResolver#performMeleeAttack}); only victim selection
 * differs - the claim's UUID, not the nearest hostile in the cone -
 * which is what lets passive prey (the hunt face) and the defend
 * target take the addressed hit.
 *
 * <p>Deliberate deviation from the vanilla client press: a player
 * hurts what the crosshair names, the device resolves the claim's
 * entity id against the server level - the same mechanical
 * equivalent the melee path replaced with a cone. Gates mirror the
 * cone path's discipline: the shared melee surface reach, the
 * resolver's line-of-sight and lava checks (no swinging through
 * cover a walled target owns - the holdsFireWhenSightBlocked pin),
 * and the resolver's item-derived cooldown, self-timed here, which
 * keeps a fast claim pulse from leaking machine-gun hits.
 *
 * <p>Contract: see boundaries.md decision 25 (all mutation behind the
 * claim surface); USE stays entity-melee per the 0007 review.
 *
 * <p>Implementation note: runs on the server tick thread only, called
 * from BindingActor.flush on each new Strike claim.
 */
// contract: see boundaries.md decision 25 (all mutation behind the claim
//            surface); USE stays entity-melee per the 0007 review
public final class StrikeEntityExecutor {

    private final BotBodyEntity body;

    private final BotPlayerFacade facade;

    private final MeleeResolver resolver;

    /** Game time of the last landed strike; -1 = never struck. */
    private long lastStrikeTick = -1;

    /**
     * Creates an executor bound to one body acting through one facade
     * and sharing the resolver's damage chain.
     *
     * @param body     the physical carrier whose level resolves the
     *                 target and whose attributes feed the damage
     *                 chain; never null
     * @param facade   the Player-typed acting surface; never null
     * @param resolver the melee resolver whose crit-aware damage chain
     *                 every landed strike rides; never null
     */
    public StrikeEntityExecutor(BotBodyEntity body, BotPlayerFacade facade, MeleeResolver resolver) {
        this.body = body;
        this.facade = facade;
        this.resolver = resolver;
    }

    /**
     * Run one directed strike and report whether it landed. A
     * missing, removed, out-of-reach, or uncharged state is a silent
     * no-op - the behavior tier's cadence simply presses again, and
     * no cooldown or charge is consumed on any no-op path.
     *
     * @param entityId the target entity's UUID string; never null
     * @return true when the strike landed
     */
    public boolean strike(String entityId) {
        LivingEntity target = resolve(entityId);
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        double surfDistSq = target.getBoundingBox().distanceToSqr(body.getEyePosition());
        if (surfDistSq > MeleeResolver.MELEE_REACH_SURFACE * MeleeResolver.MELEE_REACH_SURFACE) {
            return false;
        }
        // The wall discipline the cone path carries: no swing through
        // blocked sight or lava. Vanilla Player.attack gates on reach
        // only, but the resolver's deviation (cone + LOS + lava) is
        // what keeps a walled target undamageable - the addressed
        // strike inherits it, or a directed order could hit through
        // cover the reflex path must respect.
        var eye = body.getEyePosition();
        var targetCenter = target.position().add(0, target.getBbHeight() / 2, 0);
        if (resolver.sightBlocked(eye, targetCenter) || resolver.lavaBetween(eye, targetCenter)) {
            return false;
        }
        // The body is a PathfinderMob with no vanilla charge ticker,
        // so the resolver's own discipline applies: the held weapon's
        // item-derived cooldown, self-timed, and a no-op path consumes
        // nothing - a claim pulse faster than the attack speed cannot
        // leak machine-gun hits.
        long now = body.level().getGameTime();
        if (lastStrikeTick >= 0 && now - lastStrikeTick < resolver.getAttackCooldownTicks()) {
            return false;
        }
        // The damage chain reads the actor's position and look angles
        // (knockback direction, crit fall state) - mirror the body
        // first, same as the interaction and block chains.
        facade.syncPosition();
        resolver.performMeleeAttack(target);
        lastStrikeTick = now;
        return true;
    }

    /**
     * Resolve the claim's id against the body's server level.
     *
     * @param entityId the target entity's UUID string; never null
     * @return the living entity, or null when the id is malformed,
     *         unknown to the level, or not a living entity
     */
    private LivingEntity resolve(String entityId) {
        UUID id;
        try {
            id = UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return ((ServerLevel) body.level()).getEntity(id) instanceof LivingEntity living ? living : null;
    }
}
