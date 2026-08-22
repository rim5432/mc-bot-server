package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.Objects;

/**
 * Combat micro-execution per boundaries.md decision 11: aim at the
 * ordered target and time swings. Deliberately owns NO locomotion -
 * the chase is a {@link com.mcbot.mcbotserver.core.pathing} concern
 * driven by the directive's goal; this behavior only adds ROT aim and
 * USE swing claims on top.
 *
 * <p>Contract: see boundaries.md decision 11 and decision 14 (four
 * channels - melee rides USE as "act with main hand"; the adapter
 * resolves what a swing hits). A directive without a combat order is
 * ignored on the first line: goto missions must feel nothing from
 * this behavior existing.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (behavior owns micro-execution)
public final class CombatBehavior implements Behavior {

    /** Distance from the aim point within which a swing may fire. */
    public static final double ATTACK_REACH = 3.0;

    /**
     * Horizontal distance below which the aim bearing is held, not
     * recomputed - overlapping a target makes direction noise. The
     * threshold sits just under the player's half-width hitbox
     * (0.6 wide / 2 = 0.3 each side, plus a little margin for the
     * cap cell) so the body has to be measurably out of overlap
     * before the bearing is allowed to swing again; below it the
     * 180-degree-per-tick yaw flip dominates the atan2 signal and
     * every dot-product check against the aim cone fails.
     */
    public static final double AIM_MIN_HORIZONTAL = 0.5;

    /** Minimum ticks between swings (vanilla invulnerability scale). */
    public static final int ATTACK_COOLDOWN_TICKS = 10;

    private final String name;
    private final PoseSource poseSource;
    private int ticksSinceSwing = ATTACK_COOLDOWN_TICKS;
    private boolean pressLatched;
    private float lastYaw;

    /**
     * Fine-grained body position, same shape as the pathing mover's.
     */
    @FunctionalInterface
    public interface PoseSource {

        /**
         * Current body position in world doubles.
         *
         * @return the pose; never null
         */
        Vec3 get();
    }

    /**
     * Creates a combat behavior over one body pose source.
     *
     * @param name       stable identity for claims; never null or blank
     * @param poseSource body position accessor; never null
     */
    public CombatBehavior(String name, PoseSource poseSource) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.poseSource = Objects.requireNonNull(poseSource,
            "poseSource");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ExecutionReport tick(WorldView world, Directive directive,
                                Actor actor) {
        if (directive == null
            || directive.overrides().combat() == null) {
            return ExecutionReport.running();
        }

        Vec3 pose = poseSource.get();
        CellPos aimCell = aimPointOf(directive.goal());
        double tx = aimCell.x() + 0.5;
        double ty = aimCell.y() + 1.0;
        double tz = aimCell.z() + 0.5;

        double dx = tx - pose.x();
        double dy = ty - pose.y();
        double dz = tz - pose.z();

        // Aim degeneracy guard: when standing on top of the target,
        // horizontal direction is noise and the computed yaw flips
        // 180 degrees every tick. Hold the last bearing instead.
        double horizontal = Math.hypot(dx, dz);
        float yaw = lastYaw;
        if (horizontal >= AIM_MIN_HORIZONTAL) {
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            lastYaw = yaw;
        }
        float pitch = (float) -Math.toDegrees(
            Math.atan2(dy, Math.max(horizontal, 0.001)));
        actor.submit(new Claim(Channel.ROT, 20, name,
            new Intent.Look(yaw, pitch)));

        // Swing pacing: hold the release until cooldown expires so the
        // adapter sees exactly one rising edge per attack window.
        boolean released = !pressLatched;
        ticksSinceSwing++;
        double reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (released && ticksSinceSwing >= ATTACK_COOLDOWN_TICKS
            && reach <= ATTACK_REACH) {
            actor.submit(new Claim(Channel.USE, 20, name,
                new Intent.Use(true)));
            pressLatched = true;
            ticksSinceSwing = 0;
        } else if (pressLatched) {
            actor.submit(new Claim(Channel.USE, 20, name,
                new Intent.Use(false)));
            pressLatched = false;
        }
        return ExecutionReport.running();
    }

    /**
     * Where to aim for the current directive's target. Exhaustive over
     * the sealed goal algebra; both goal forms carry the target cell.
     *
     * @param goal the directive's locomotion goal; never null
     * @return the cell whose center the body should face; never null
     */
    private static CellPos aimPointOf(Goal goal) {
        if (goal instanceof GoalBlock b) {
            return b.target();
        }
        if (goal instanceof GoalNear n) {
            return n.center();
        }
        throw new IllegalStateException(
            "unhandled goal variant: " + goal.getClass());
    }
}
