package com.mcbot.mcbotserver.api.actor;

/**
 * One per-tick request to own a physical channel.
 *
 * <p>Contract: see ADR-0004 D2. Claims are re-asserted every tick and
 * expire at flush — there is no persistent ownership, so silence means
 * "no intent" and an unwon MOVE channel produces a halt (a body with no
 * client must be told to stand still every tick). Resolution: higher
 * {@code priority} wins; ties favor the incumbent holder of the channel
 * from the previous flush, which kills look-direction jitter between
 * pathing and combat.
 *
 * @param channel  which physical output is contested; never null
 * @param priority claim strength within the behavior layer's own
 *                 convention; higher wins
 * @param holder   stable name of the claiming behavior, used for the
 *                 incumbent tie-break and diagnostics; never null or
 *                 blank
 * @param intent   the payload to apply when this claim wins; must be
 *                 the sealed variant matching the channel
 */
public record Claim(Channel channel, int priority, String holder,
                    Intent intent) {

    /**
     * Creates a validated claim.
     *
     * @param channel  must not be null
     * @param priority any int; bands live in the caller's convention
     * @param holder   must not be null or blank
     * @param intent   must not be null
     */
    public Claim {
        if (channel == null) {
            throw new IllegalArgumentException("channel must not be null");
        }
        if (holder == null || holder.isBlank()) {
            throw new IllegalArgumentException("holder must not be blank");
        }
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
    }
}
