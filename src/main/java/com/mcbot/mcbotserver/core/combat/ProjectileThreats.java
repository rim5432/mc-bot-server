package com.mcbot.mcbotserver.core.combat;

import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.ProjectileSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Incoming-projectile geometry over the projectile scan: which
 * in-flight projectile, if any, is closing on the body closely enough
 * to count as a hit course. Pure math over api types - no engine
 * types, no state, no decisions beyond "is this flight aimed at the
 * body".
 *
 * <p>Deliberately straight-line: gravity curves an arrow's path, but
 * inside the scan band the accumulated drop (0.05 b/t^2 over a 3 b/t
 * flight's five ticks - under two-thirds of a block at the rim) fits
 * inside the hit-course margin, and a parabolic solver would buy
 * precision the 5-tick shield arm delay cannot use anyway.
 *
 * <p>Implementation note: pure function; runs on the server tick
 * thread.
 */
public final class ProjectileThreats {

    /** Scan radius in blocks; the standoff band - farther flights re-enter it. */
    public static final double SCAN_RADIUS = 15.0;

    /**
     * Closest approach that counts as a hit course, blocks - a
     * one-block guard band around the body. True collision needs only
     * the body's half-width plus the arrow's, but a graze-distance
     * flight is still aimed AT the body (arrows curve, bodies step),
     * and the earlier lock buys reaction ticks the vanilla 5-tick
     * arm delay spends fast.
     */
    public static final double HIT_COURSE_MARGIN = 1.2;

    /** Flights slower than this are spent or stuck, not threats. */
    public static final double MIN_THREAT_SPEED = 0.2;

    /** Arrival horizon in ticks; later arrivals get re-scanned on the way. */
    public static final int ETA_TICKS = 25;

    private ProjectileThreats() {}

    /**
     * The most urgent projectile on a hit course with the body: the
     * one with the smallest arrival estimate. The body's own shots
     * self-filter - they recede, and a receding flight never closes.
     *
     * @param world read surface for the projectile scan; never null
     * @param body  the defended body position (eye pose); never null
     * @return the nearest-arrival threat, or null when no flight
     *         closes on the body
     */
    @Feature(
            id = "combat.shield.incoming_projectile_detection",
            face = "combat.shield",
            description = "Incoming-projectile detection: closest-approach geometry over the projectile scan - a flight"
                    + " is a threat when its speed >= 0.2 b/t, its straight-line course passes within 1.2 blocks of"
                    + " the body, and arrival is inside 25 ticks. Answers the smallest-arrival threat; receding"
                    + " flights (including the bot's own shots) never close and self-filter.",
            vanillaRef = "Player shield use against incoming arrows (decompiled 1.20.1)",
            deviation = "Bot-specific: straight-line closest-approach, no gravity term - inside the 15-block band the"
                    + " arrow drop fits inside the hit-course margin, and the 5-tick vanilla shield arm delay"
                    + "(isBlocking requires 5 held ticks) bounds useful precision harder than the geometry does.")
    @Nullable
    public static ProjectileSnapshot incomingThreat(WorldView world, Vec3 body) {
        CellPos center =
                new CellPos((int) Math.floor(body.x()), (int) Math.floor(body.y()), (int) Math.floor(body.z()));
        List<ProjectileSnapshot> flights = world.getProjectiles(center, SCAN_RADIUS, ViewMode.LIVE);
        ProjectileSnapshot best = null;
        double bestEta = Double.MAX_VALUE;
        for (ProjectileSnapshot flight : flights) {
            Vec3 pos = flight.pos();
            Vec3 velocity = flight.velocity();
            double speedSq = velocity.x() * velocity.x() + velocity.y() * velocity.y() + velocity.z() * velocity.z();
            if (speedSq < MIN_THREAT_SPEED * MIN_THREAT_SPEED) {
                continue;
            }
            double rx = body.x() - pos.x();
            double ry = body.y() - pos.y();
            double rz = body.z() - pos.z();
            double relDotV = rx * velocity.x() + ry * velocity.y() + rz * velocity.z();
            if (relDotV <= 0) {
                // Not closing: receding or crossing beam - not a hit course.
                continue;
            }
            double eta = relDotV / speedSq;
            if (eta > ETA_TICKS) {
                continue;
            }
            // Point-to-line distance at the closest-approach tick.
            double relSq = rx * rx + ry * ry + rz * rz;
            double closestSq = relSq - relDotV * relDotV / speedSq;
            if (closestSq > HIT_COURSE_MARGIN * HIT_COURSE_MARGIN) {
                continue;
            }
            if (eta < bestEta) {
                best = flight;
                bestEta = eta;
            }
        }
        return best;
    }
}
