package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * Sneak edge guard - a verbatim clone of
 * {@code Player.maybeBackOffFromEdge} (decompiled 1.20.1 Player.java
 * lines 1116-1168) with its {@code isAboveGround} gate (lines
 * 1170-1173), extracted from BotBodyEntity so the carrier keeps only
 * its engine-override surface. The base Entity implementation is a
 * no-op (returns the movement vector unchanged), so without this
 * algorithm a sneaking bot walks straight off ledges.
 *
 * <p>Conditions (all must hold): the mover type is SELF (the binding
 * drives movement through LivingEntity.travel to move(SELF, delta)),
 * the movement has no upward component (y &lt;= 0 - jumping off a
 * ledge is still allowed), shift is held ({@code isShiftKeyDown},
 * which Player exposes as {@code isStayingOnGroundSurface}), and the
 * body is above ground (on ground or close enough that a block is
 * within step height below).
 *
 * <p>Algorithm: reduce x and z in 0.05 increments while the body,
 * offset by (dx, -maxUpStep, dz), has no collision - no collision
 * means no ground below that offset, i.e. the body would walk off
 * the edge. The loop stops at the largest dx/dz that still has
 * ground below, so the body creeps right up to the edge but never
 * past it. Three passes: x-only, z-only, then combined diagonal.
 */
final class SneakEdgeGuard {

    /** One reduction increment, vanilla's 0.05 block granularity. */
    private static final double STEP = 0.05;

    private SneakEdgeGuard() {}

    /**
     * Clamps a sneaking body's horizontal movement so it cannot walk
     * off a ledge. Returns the input unchanged whenever any gating
     * condition fails (see class Javadoc).
     *
     * @param body      the sneaking body; never null
     * @param movement  the intended movement vector; never null
     * @param moverType the mover type; never null
     * @return the edge-clamped movement vector; never null
     */
    static Vec3 clamp(Entity body, Vec3 movement, MoverType moverType) {
        if (moverType != MoverType.SELF || movement.y > 0.0 || !body.isShiftKeyDown() || !isAboveGround(body)) {
            return movement;
        }
        double dx = backOffX(body, movement.x);
        double dz = backOffZ(body, movement.z);
        while (dx != 0.0
                && dz != 0.0
                && body.level().noCollision(body, body.getBoundingBox().move(dx, -body.maxUpStep(), dz))) {
            dx = backOffStep(dx);
            dz = backOffStep(dz);
        }
        return new Vec3(dx, movement.y, dz);
    }

    /**
     * The x-only pass: reduces dx while the body, offset by
     * (dx, -maxUpStep, 0), has no ground below it.
     *
     * @param body the sneaking body; never null
     * @param dx   the x component to reduce
     * @return the largest-magnitude dx that keeps ground below
     */
    private static double backOffX(Entity body, double dx) {
        while (dx != 0.0 && body.level().noCollision(body, body.getBoundingBox().move(dx, -body.maxUpStep(), 0.0))) {
            dx = backOffStep(dx);
        }
        return dx;
    }

    /**
     * The z-only pass: reduces dz while the body, offset by
     * (0, -maxUpStep, dz), has no ground below it.
     *
     * @param body the sneaking body; never null
     * @param dz   the z component to reduce
     * @return the largest-magnitude dz that keeps ground below
     */
    private static double backOffZ(Entity body, double dz) {
        while (dz != 0.0 && body.level().noCollision(body, body.getBoundingBox().move(0.0, -body.maxUpStep(), dz))) {
            dz = backOffStep(dz);
        }
        return dz;
    }

    /**
     * One 0.05 increment of the reduction: below one increment means
     * zero (the sentinel that ends the pass), otherwise toward zero
     * from whichever side the delta sits on.
     *
     * @param delta current axis component
     * @return the next delta value
     */
    private static double backOffStep(double delta) {
        if (Math.abs(delta) < STEP) {
            return 0.0;
        }
        return delta > 0.0 ? delta - STEP : delta + STEP;
    }

    /**
     * Whether the body is close enough to ground that the edge guard
     * should engage. Mirrors {@code Player.isAboveGround}
     * (decompiled 1.20.1 Player.java lines 1170-1173): on ground
     * always qualifies; a falling body qualifies only when
     * fallDistance is below step height AND a block exists within
     * that distance below (noCollision returns false). A body
     * already over open air (no block below) is excluded - the guard
     * only prevents walking OFF an edge, not mid-air steering.
     *
     * @param body the sneaking body; never null
     * @return true when the edge guard should apply
     */
    private static boolean isAboveGround(Entity body) {
        return body.onGround()
                || (body.fallDistance < body.maxUpStep()
                        && !body.level()
                                .noCollision(
                                        body,
                                        body.getBoundingBox().move(0.0, body.fallDistance - body.maxUpStep(), 0.0)));
    }
}
