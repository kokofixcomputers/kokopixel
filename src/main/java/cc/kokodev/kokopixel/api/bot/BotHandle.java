package cc.kokodev.kokopixel.api.bot;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * A handle on a packet-based fake bot player spawned in the world.
 * Passed to {@link BotController} so the AI can drive the bot.
 *
 * <p>All movement/state mutations are applied immediately via NMS packets
 * broadcast to every player in the same world — no real client is involved.
 *
 * <p>Use {@link #getSenses()} to perceive the world and {@link #getActions()}
 * for higher-level actions (pathfinding, block-breaking, attacking, etc.).
 */
public interface BotHandle {

    /** The bot's UUID (stable across the lifetime of this bot). */
    UUID getUniqueId();

    /** The name shown above the bot's head. */
    String getName();

    /** Current location in the game world. */
    Location getLocation();

    // -------------------------------------------------------------------------
    // Movement
    // -------------------------------------------------------------------------

    /** Teleport the bot instantly (absolute coordinates). */
    void teleport(Location location);

    /** Move the bot by a relative delta from its current position. */
    void move(double dx, double dy, double dz, float yaw, float pitch);

    /** Update only head yaw (e.g. looking around without moving). */
    void setHeadRotation(float yaw, float pitch);

    // -------------------------------------------------------------------------
    // State / animations
    // -------------------------------------------------------------------------

    void setSneaking(boolean sneaking);
    void setSprinting(boolean sprinting);

    /** Swing the main hand (attack animation). */
    void swingMainHand();

    // -------------------------------------------------------------------------
    // Equipment
    // -------------------------------------------------------------------------

    void setHelmet(ItemStack item);
    void setChestplate(ItemStack item);
    void setLeggings(ItemStack item);
    void setBoots(ItemStack item);
    void setMainHand(ItemStack item);
    void setOffHand(ItemStack item);

    // -------------------------------------------------------------------------
    // Perception & higher-level actions
    // -------------------------------------------------------------------------

    /** The bot's perception layer — use this to scan blocks, find enemies, check FOV, etc. */
    BotSenses getSenses();

    /** The bot's action layer — pathfinding, block-breaking, bridging, attacking, etc. */
    BotActions getActions();

    /** The world the bot lives in. */
    World getWorld();
}
