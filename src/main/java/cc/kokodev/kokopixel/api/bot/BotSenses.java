package cc.kokodev.kokopixel.api.bot;

import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameTeam;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * The bot's "eyes" — read-only world perception API.
 *
 * <p>All methods are pure queries over the Bukkit world; none of them move or
 * animate the bot.  Call these from {@link BotController#onTick} to build
 * awareness, then drive the bot via {@link BotActions}.
 */
public interface BotSenses {

    // -------------------------------------------------------------------------
    // Vision / FOV
    // -------------------------------------------------------------------------

    /**
     * Returns true if the block at {@code target} is within the bot's horizontal
     * field-of-view and within {@code maxDistance} blocks.
     *
     * <p>Uses a standard 70° half-angle (140° total) matching Minecraft's default FOV.
     * Pass a custom {@code fovHalfDegrees} to override (e.g. 45 for a narrow cone).
     */
    boolean canSeeLocation(Location target, double maxDistance, float fovHalfDegrees);

    /** Convenience overload using the default 70° half-angle. */
    default boolean canSeeLocation(Location target, double maxDistance) {
        return canSeeLocation(target, maxDistance, 70f);
    }

    /**
     * True if there is an unobstructed line-of-sight between the bot's eye
     * position and the centre of the given block (ignores passable blocks like
     * air, grass, flowers).
     */
    boolean hasLineOfSight(Block block);

    /** True if there is line-of-sight to a player's eye position. */
    boolean hasLineOfSight(Player player);

    // -------------------------------------------------------------------------
    // Block scanning
    // -------------------------------------------------------------------------

    /**
     * Returns all non-air blocks within {@code radius} blocks of the bot,
     * optionally filtered by material.
     */
    List<Block> getNearbyBlocks(double radius, Material... filter);

    /**
     * Finds the nearest block of one of the given materials within {@code radius},
     * or empty if none found.
     */
    Optional<Block> findNearestBlock(double radius, Material... materials);

    /**
     * Returns the block the bot is currently looking at (ray-cast up to
     * {@code maxDistance} blocks from the eye position), or empty if no solid
     * block is hit.
     */
    Optional<Block> getTargetBlock(double maxDistance);

    /**
     * Returns the block directly below the bot's feet, useful for
     * checking whether the bot is on solid ground.
     */
    Block getBlockBelow();

    /**
     * Returns true if the bot is standing on solid ground (the block below
     * is not air/passable and the bot's feet are within 0.1 of the surface).
     */
    boolean isOnGround();

    /**
     * Returns true if the bot would step off a ledge by moving in its current
     * facing direction (checks one block forward, one block down).
     */
    boolean isAtEdge();

    /**
     * Returns the block at the bot's feet + 1 (head level).
     * Useful for checking headroom before jumping or crouching.
     */
    Block getBlockAtHead();

    // -------------------------------------------------------------------------
    // Player / entity perception
    // -------------------------------------------------------------------------

    /**
     * Returns all real players (non-bot) in the game world who are within
     * {@code radius} blocks of the bot.
     */
    List<Player> getNearbyPlayers(double radius);

    /**
     * Returns the nearest player within {@code radius}, or empty.
     * Does not filter by team — use {@link #getNearestEnemy} for that.
     */
    Optional<Player> getNearestPlayer(double radius);

    /**
     * Returns the nearest living enemy (player not on the same team as the bot)
     * within {@code radius}, or empty.  Requires {@code game} for team context.
     */
    Optional<GamePlayer> getNearestEnemy(double radius, GameInstance game);

    /**
     * Returns the nearest ally (same team) within {@code radius}, or empty.
     */
    Optional<GamePlayer> getNearestAlly(double radius, GameInstance game);

    /**
     * Returns all living players on alive (non-eliminated) enemy teams.
     */
    List<GamePlayer> getVisibleEnemies(GameInstance game);

    // -------------------------------------------------------------------------
    // Team awareness
    // -------------------------------------------------------------------------

    /** Returns all teams in the game that are still alive (not eliminated). */
    List<GameTeam> getAliveTeams(GameInstance game);

    /**
     * Returns the team this bot belongs to, or empty if the bot is not
     * assigned to a team (e.g. free-for-all games).
     */
    Optional<GameTeam> getOwnTeam(GameInstance game);

    /**
     * Returns true if the given player is on the same team as this bot.
     */
    boolean isAlly(Player player, GameInstance game);

    /**
     * Returns true if the given game player is on an enemy team.
     */
    boolean isEnemy(GamePlayer player, GameInstance game);

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /** Horizontal distance (ignores Y) from the bot to a location. */
    double horizontalDistanceTo(Location location);

    /** 3D Euclidean distance from the bot's feet to a location. */
    double distanceTo(Location location);

    /**
     * Computes the yaw angle (degrees) the bot must face to look toward
     * {@code target} from its current position.
     */
    float yawToward(Location target);

    /**
     * Computes the pitch angle (degrees) the bot must face to look toward
     * {@code target} (positive = looking down, negative = looking up).
     */
    float pitchToward(Location target);
}
