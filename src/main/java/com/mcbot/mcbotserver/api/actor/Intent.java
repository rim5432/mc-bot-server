package com.mcbot.mcbotserver.api.actor;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Direction;
import com.mcbot.mcbotserver.api.types.Vec3;

/**
 * The closed vocabulary of physical intents. Values are pure data — an
 * intent never computes physics; the vanilla engine is the only thing
 * that moves the body.
 *
 * <p>Contract: see boundaries.md section A (Actor is intents-only) and
 * numen-notes.md section 4 (InputDriver: the smallest possible input
 * emulation). Sealed so the adapter's exhaustive dispatch stays honest;
 * new channels mean editing this file, not casting from Object.
 */
public sealed interface Intent {

    /**
     * Locomotion drive, resolved on the MOVE channel.
     *
     * @param forward -1..1, +1 = full ahead
     * @param strafe  -1..1, +1 = full left per engine convention
     * @param jump    true to request a jump this tick
     * @param sneak   true to hold the sneak modifier
     */
    record Move(double forward, double strafe,
                boolean jump, boolean sneak) implements Intent {
    }

    /**
     * Absolute rotation target, resolved on the ROT channel.
     *
     * @param yawDeg   target body yaw in degrees
     * @param pitchDeg target view pitch in degrees
     */
    record Look(float yawDeg, float pitchDeg) implements Intent {
    }

    /**
     * Attack / interact press, resolved on the USE channel. Aiming is
     * the ROT channel's job; this only carries the press semantics.
     *
     * @param pressing true to hold the action down this tick
     */
    record Use(boolean pressing) implements Intent {
    }

    /**
     * Hotbar selection, resolved on the SLOT channel.
     *
     * @param slot hotbar index 0..8
     */
    record SelectSlot(int slot) implements Intent {

        /**
         * Creates a validated selection.
         *
         * @param slot must be within 0..8 inclusive
         */
        public SelectSlot {
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException(
                    "slot must be in 0..8, got " + slot);
            }
        }
    }

    /**
     * Held dig at one block cell, resolved on the INTERACT channel.
     * The claim's presence this tick IS the button state - holding a
     * dig means re-asserting the claim every tick; the adapter
     * accumulates destroy progress across those ticks and breaks the
     * block when it completes (the vanilla ServerPlayerGameMode
     * shape, mirrored adapter-side). The engine applies all world
     * mutation; boundary A holds - this record only names a cell.
     *
     * @param target the block cell being dug; never null
     */
    record Dig(CellPos target)
            implements Intent {

        /**
         * Creates a validated dig intent.
         *
         * @param target must not be null
         */
        public Dig {
            if (target == null) {
                throw new IllegalArgumentException(
                    "dig target must not be null");
            }
        }
    }

    /**
     * Drop the selected hotbar item, resolved on the INTERACT channel.
     * One-shot action: the adapter fires on the rising edge (claim
     * present this tick, absent last tick) and spawns an item entity
     * via {@code Entity.spawnAtLocation}, then clears or shrinks the
     * source slot. Re-asserting the claim across ticks does not drop
     * twice — the rising-edge gate is the same shape USE uses for
     * melee swings (issue 0007 Phase 1).
     *
     * @param fullStack true to drop the entire stack; false to drop a
     *                   single item (vanilla Q vs Ctrl-Q distinction)
     */
    record DropSelected(boolean fullStack) implements Intent {
    }

    /**
     * Right-click a block: place a block from the selected hotbar slot
     * onto the clicked face, resolved on the INTERACT channel. One-shot
     * action — the adapter fires on the rising edge (same shape USE uses
     * for melee swings and DropSelected uses for item drops); a held
     * claim across ticks does not place repeatedly.
     *
     * <p>Phase 1 scope (issue 0007): only block placement is implemented
     * — the adapter checks that the selected item is a BlockItem,
     * computes the placement cell as {@code target.relative(face)}, and
     * calls {@code level.setBlock} directly (bypassing
     * ServerPlayerGameMode.useItemOn, which requires a ServerPlayer the
     * carrier does not have until Phase 2's BotPlayerFacade). Use-block
     * interactions (open a chest, press a button) and direction-aware
     * block states (stairs, pistons) are deferred to Phase 2 — they need
     * either a Player parameter or BlockState.getStateForPlacement context.
     *
     * <p>The placement position depends on the face: placing on the UP
     * face puts the block above the target, on the NORTH face puts it
     * north of the target, etc. {@code hitPos} is the absolute
     * intersection point of the ray with the cube face — carried for
     * Phase 2 direction-aware placement and sub-face targeting (e.g.
     * which half of a stair), but not used by the Phase 1 executor.
     *
     * @param target the clicked block cell; never null
     * @param face   which face of the target was clicked; never null
     * @param hitPos absolute intersection point of the ray with the face;
     *               never null
     */
    record InteractBlock(CellPos target, Direction face, Vec3 hitPos)
            implements Intent {

        /**
         * Creates a validated interact-block intent.
         *
         * @param target must not be null
         * @param face   must not be null
         * @param hitPos must not be null
         */
        public InteractBlock {
            if (target == null) {
                throw new IllegalArgumentException(
                    "interact target must not be null");
            }
            if (face == null) {
                throw new IllegalArgumentException(
                    "interact face must not be null");
            }
            if (hitPos == null) {
                throw new IllegalArgumentException(
                    "interact hitPos must not be null");
            }
        }
    }
}
