package cc.kokodev.kokopixel.api.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Higher-level action API for the bot.
 *
 * <p>Each method translates a logical action into one or more packet calls and
 * world mutations.  Actions that span multiple ticks (e.g. block-breaking,
 * pathfinding) are executed incrementally — call them every tick from
 * {@link BotController#onTick} and they will progress automatically.
 */
public interface BotActions {

    // -------------------------------------------------------------------------
    // Locomotion
    // -------------------------------------------------------------------------

    /**
     * Moves the bot one step toward {@code target} at {@code speed} blocks/tick,
     * updating the bot's yaw to face the direction of movement.
     * Returns {@code true} if the bot has arrived (within 0.4 blocks).
     *
     * <p>This is a simple straight-line step — combine with {@link #pathfindTo}
     * for obstacle-aware movement.
     */
    boolean walkToward(Location target, double speed);

    /**
     * Runs a single A* step toward {@code target} and moves the bot along the
     * resulting path at {@code speed} blocks/tick.
     * Returns {@code true} once the destination is reached.
     *
     * <p>Call every tick; the path is recalculated automatically when it
     * becomes stale (target moved > 2 blocks, or path is blocked).
     *
     * @param maxNodes maximum nodes the A* search may expand per call (tunable for CPU cost)
     */
    boolean pathfindTo(Location target, double speed, int maxNodes);

    /** Convenience overload with a default of 256 max nodes. */
    default boolean pathfindTo(Location target, double speed) {
        return pathfindTo(target, speed, 256);
    }

    /**
     * Makes the bot jump (applies an upward velocity packet).
     * Has no effect if the bot is not on the ground.
     */
    void jump();

    /**
     * Sets sprinting state and adjusts the effective movement speed in
     * subsequent {@link #walkToward} / {@link #pathfindTo} calls.
     */
    void setSprinting(boolean sprinting);

    /** Sets sneaking state (also slows movement). */
    void setSneaking(boolean sneaking);

    // -------------------------------------------------------------------------
    // Combat
    // -------------------------------------------------------------------------

    /**
     * Faces the bot toward {@code target} and plays the attack animation.
     * Deals no actual damage — game plugins handle damage in their own listeners
     * using the game's damage model.  Call {@link #swingMainHand()} separately
     * if you want the animation without the look.
     */
    void attack(Player target);

    /** Plays the main-hand swing animation. */
    void swingMainHand();

    // -------------------------------------------------------------------------
    // Block interaction
    // -------------------------------------------------------------------------

    /**
     * Simulates breaking {@code block} over multiple ticks.
     * Each call advances break progress by one tick worth of digging speed
     * (calculated from the bot's held item).
     * Returns {@code true} when the block is fully broken and removed.
     *
     * <p>Send {@link #swingMainHand()} on the same tick for visual feedback.
     */
    boolean breakBlock(Block block);

    /**
     * Places a block of {@code material} against the given face of {@code against}.
     * Returns {@code false} if the placement location is already occupied or out
     * of reach (> 4.5 blocks from bot eye).
     */
    boolean placeBlock(Block against, BlockFace face, Material material);

    /**
     * High-level bridge helper: places a block of {@code material} one step
     * behind the bot's current trailing edge.  Intended to be called every
     * tick while the bot is walking forward.
     * Returns {@code true} if a block was placed, {@code false} if not needed.
     */
    boolean bridgeStep(Material material);

    // -------------------------------------------------------------------------
    // Look
    // -------------------------------------------------------------------------

    /**
     * Smoothly rotates the bot's head toward {@code target} by at most
     * {@code maxDegPerTick} degrees per tick.
     * Returns {@code true} when the bot is fully aimed.
     */
    boolean lookAt(Location target, float maxDegPerTick);

    /** Immediately snaps the bot's head to look at {@code target}. */
    void lookAtInstant(Location target);

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the best tool in the bot's "virtual" held-item slot for breaking
     * the given block material (from the list supplied to
     * {@link #setInventory(List)}).
     */
    ItemStack bestToolFor(Material blockMaterial);

    /**
     * Sets the bot's virtual inventory (used by {@link #breakBlock} speed
     * calculations and {@link #bestToolFor}).
     * Does not affect visual equipment — call {@link BotHandle#setMainHand} separately.
     */
    void setInventory(List<ItemStack> items);

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    /** Returns the currently active pathfinding path, or an empty list if idle. */
    List<Location> getCurrentPath();

    /** Clears the current pathfinding path so the next call to pathfindTo starts fresh. */
    void clearPath();

    /** Returns true if the bot is currently in a jump (airborne). */
    boolean isJumping();

    /** Returns true if the bot is currently sprinting. */
    boolean isSprinting();

    /** Returns true if the bot is currently sneaking. */
    boolean isSneaking();
}
