package com.mcbot.mcbotserver.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.ProjectileSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the incoming-projectile geometry: a flight is a
 * threat only when it is fast, closing, and on a hit course within
 * the arrival horizon - receding flights (including the bot's own
 * shots), spent arrows, crossing beams, and distant arrivals all
 * answer no threat. Pure math over injected snapshots; the adapter's
 * Projectile scan itself is engine territory (gametest).
 */
class ProjectileThreatsTest {

    private static final Vec3 BODY = new Vec3(0, 64, 0);

    @Test
    void closingFlightIsAThreat() {
        MockWorldView world = new MockWorldView();
        world.addProjectile(new ProjectileSnapshot(new Vec3(8, 64, 0), new Vec3(-1, 0, 0)));

        ProjectileSnapshot threat = ProjectileThreats.incomingThreat(world, BODY);

        assertNotNull(threat, "a flight aimed at the body must be a threat");
    }

    @Test
    void recedingFlightIsNotAThreat() {
        MockWorldView world = new MockWorldView();
        // An arrow just fired by the bot: behind it, moving away.
        world.addProjectile(new ProjectileSnapshot(new Vec3(-1, 64, 0), new Vec3(-3, 0, 0)));

        assertNull(ProjectileThreats.incomingThreat(world, BODY), "the bot's own shot must self-filter");
    }

    @Test
    void crossingBeamIsNotAThreat() {
        MockWorldView world = new MockWorldView();
        // Parallel track eight blocks north, moving west: never closes.
        world.addProjectile(new ProjectileSnapshot(new Vec3(8, 64, 8), new Vec3(-1, 0, 0)));

        assertNull(ProjectileThreats.incomingThreat(world, BODY), "a crossing beam is not a hit course");
    }

    @Test
    void spentArrowIsNotAThreat() {
        MockWorldView world = new MockWorldView();
        // Embedded in the ground half a block away: zero velocity.
        world.addProjectile(new ProjectileSnapshot(new Vec3(0.5, 63.5, 0.5), new Vec3(0, 0, 0)));

        assertNull(ProjectileThreats.incomingThreat(world, BODY), "a spent arrow has no hit course");
    }

    @Test
    void flightOutsideTheHorizonIsNotAThreat() {
        MockWorldView world = new MockWorldView();
        // Inside the scan radius (12 away) but drifting: arrival in 60 ticks.
        world.addProjectile(new ProjectileSnapshot(new Vec3(12, 64, 0), new Vec3(-0.2, 0, 0)));

        assertNull(ProjectileThreats.incomingThreat(world, BODY), "arrivals past the horizon re-enter the scan later");
    }

    @Test
    void nearestArrivalWins() {
        MockWorldView world = new MockWorldView();
        world.addProjectile(new ProjectileSnapshot(new Vec3(9, 64, 0), new Vec3(-1, 0, 0)));
        world.addProjectile(new ProjectileSnapshot(new Vec3(4, 64, 0), new Vec3(-1, 0, 0)));

        ProjectileSnapshot threat = ProjectileThreats.incomingThreat(world, BODY);

        assertNotNull(threat, "a volley still answers a threat");
        assertEquals(4, threat.pos().x(), "whichever flight arrives first is the threat");
    }
}
