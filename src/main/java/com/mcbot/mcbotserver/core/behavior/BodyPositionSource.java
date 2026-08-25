package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.types.Vec3;

/**
 * Fine-grained body position seam: the adapter supplies it, the
 * behaviors consume it. One shared contract - PathingBehavior and
 * CombatBehavior used to carry identical nested copies under the
 * same name, which is exactly how the H9 name collision was born.
 *
 * <p>Not api vocabulary: no harness ever sees a sensor, only its
 * effects on the boundary-D stream; promoting internal wiring
 * seams to api would thicken the surface open item H1 is trying to
 * trim.
 */
@FunctionalInterface
public interface BodyPositionSource {

    /**
     * Current body position in world doubles. Doubles are
     * load-bearing: the stuck fuse measures sub-cell motion, and a
     * block-cell source reads zero while a slowly accelerating body
     * crosses its own cell.
     *
     * @return the position; never null
     */
    Vec3 get();
}
