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
public record Claim(Channel channel, int priority, String holder, Intent intent) {

    /**
     * Creates a validated claim. The intent variant must match the
     * channel — a mismatched claim would explode later inside the
     * adapter's exhaustive dispatch, and the sealed vocabulary exists
     * precisely so that failure happens here instead.
     *
     * @param channel  must not be null
     * @param priority any int; bands live in the caller's convention
     * @param holder   must not be null or blank
     * @param intent   must not be null and must be the sealed variant
     *                 matching {@code channel}
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
        if (!matchesChannel(channel, intent)) {
            throw new IllegalArgumentException(
                    "intent " + intent.getClass().getSimpleName() + " does not match channel " + channel);
        }
    }

    /**
     * Channel-to-variant pairing table; edit together with
     * {@link Intent} and {@link Channel}.
     *
     * @param channel the contested channel; never null
     * @param intent  the payload; never null
     * @return true when the payload is legal for the channel
     */
    private static boolean matchesChannel(Channel channel, Intent intent) {
        return switch (channel) {
            case MOVE -> intent instanceof Intent.Move;
            case ROT -> intent instanceof Intent.Look;
            case USE -> intent instanceof Intent.Use || intent instanceof Intent.Strike;
            case SLOT -> intent instanceof Intent.SelectSlot;
            case INTERACT ->
                intent instanceof Intent.Dig
                        || intent instanceof Intent.DropSelected
                        || intent instanceof Intent.InteractBlock
                        || intent instanceof Intent.InteractEntity;
        };
    }
}
