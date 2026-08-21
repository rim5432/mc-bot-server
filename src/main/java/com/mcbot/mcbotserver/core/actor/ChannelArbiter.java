package com.mcbot.mcbotserver.core.actor;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Reference Actor: buffers claims, resolves one winner per channel per
 * flush, expires everything at flush.
 *
 * <p>Contract: see ADR-0004 D2. Resolution rule: higher
 * {@link Claim#priority()} wins; a tie favors the holder that won the
 * previous flush (incumbent), which prevents look-direction jitter when
 * two behaviors alternate equal-priority claims. After flush the buffer
 * is empty — silence next tick means neutral output on every channel,
 * which is exactly the post-crash state {@code clearAllIntents} wants.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see ADR-0004 D2 (four channels, per-tick expiring claims)
public final class ChannelArbiter implements Actor {

    private final Map<Channel, Claim> buffer = new EnumMap<>(Channel.class);
    private final Map<Channel, String> incumbents = new HashMap<>();

    @Override
    public void submit(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim must not be null");
        }
        Claim incumbent = buffer.get(claim.channel());
        if (incumbent == null || beats(claim, incumbent)) {
            buffer.put(claim.channel(), claim);
        }
    }

    @Override
    public Map<Channel, Claim> flush() {
        Map<Channel, Claim> winners = new EnumMap<>(buffer);
        buffer.clear();
        winners.forEach((channel, claim) ->
            incumbents.put(channel, claim.holder()));
        return winners;
    }

    @Override
    public void clearAllIntents() {
        buffer.clear();
    }

    /**
     * Strict winner test between two same-channel claims. Equal
     * priorities resolve toward the holder that won the previous
     * flush, regardless of submission order this tick.
     *
     * @param challenger incoming claim; never null
     * @param current    current best claim of this tick; never null
     * @return true when the challenger displaces the current claim
     */
    private boolean beats(Claim challenger, Claim current) {
        if (challenger.priority() != current.priority()) {
            return challenger.priority() > current.priority();
        }
        String previousWinner = incumbents.get(challenger.channel());
        boolean challengerIsIncumbent =
            challenger.holder().equals(previousWinner);
        boolean currentIsIncumbent =
            current.holder().equals(previousWinner);
        return challengerIsIncumbent && !currentIsIncumbent;
    }
}
