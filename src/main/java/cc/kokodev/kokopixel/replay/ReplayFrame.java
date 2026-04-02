package cc.kokodev.kokopixel.replay;

import java.io.*;
import java.util.*;

/**
 * One tick of recorded game state.
 * Stores every tracked player's position/state and any block changes that happened this tick.
 */
public class ReplayFrame implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Per-player snapshot for this tick. */
    public final Map<UUID, PlayerSnapshot> players = new LinkedHashMap<>();
    /** Block changes that occurred this tick: key = "x,y,z", value = Material name. */
    public final List<BlockChange> blockChanges = new ArrayList<>();
    /** Custom death events that occurred this tick. */
    public final List<DeathEvent> deathEvents = new ArrayList<>();

    public static class PlayerSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final double x, y, z;
        public final float yaw, pitch;
        public final String heldItem;   // Material name or "AIR"
        public final boolean sneaking;
        public final boolean sprinting;
        public final boolean onGround;
        public final double velocityX, velocityY, velocityZ; // Added for more comprehensive recording
        public final float health; // Added for better replay accuracy
        public final int foodLevel; // Added for better replay accuracy
        public final String gameMode; // Game mode string
        public final boolean allowFlight;
        public final boolean flying;
        public final boolean invulnerable;
        public final boolean collidable;
        public final boolean canPickupItems;
        public final List<PotionEffectData> potionEffects; // Active potion effects

        public PlayerSnapshot(String name, double x, double y, double z,
                              float yaw, float pitch, String heldItem,
                              boolean sneaking, boolean sprinting, boolean onGround,
                              double velocityX, double velocityY, double velocityZ,
                              float health, int foodLevel,
                              String gameMode, boolean allowFlight, boolean flying,
                              boolean invulnerable, boolean collidable, boolean canPickupItems,
                              List<PotionEffectData> potionEffects) {
            this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.heldItem = heldItem;
            this.sneaking = sneaking;
            this.sprinting = sprinting;
            this.onGround = onGround;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.health = health;
            this.foodLevel = foodLevel;
            this.gameMode = gameMode;
            this.allowFlight = allowFlight;
            this.flying = flying;
            this.invulnerable = invulnerable;
            this.collidable = collidable;
            this.canPickupItems = canPickupItems;
            this.potionEffects = potionEffects != null ? new ArrayList<>(potionEffects) : new ArrayList<>();
        }
        
        // Backwards compatibility constructor
        public PlayerSnapshot(String name, double x, double y, double z,
                              float yaw, float pitch, String heldItem,
                              boolean sneaking, boolean sprinting, boolean onGround) {
            this(name, x, y, z, yaw, pitch, heldItem, sneaking, sprinting, onGround,
                 0, 0, 0, 20, 20, "SURVIVAL", false, false, false, true, true, new ArrayList<>());
        }
        
        // Backwards compatibility constructor with velocity/health
        public PlayerSnapshot(String name, double x, double y, double z,
                              float yaw, float pitch, String heldItem,
                              boolean sneaking, boolean sprinting, boolean onGround,
                              double velocityX, double velocityY, double velocityZ,
                              float health, int foodLevel) {
            this(name, x, y, z, yaw, pitch, heldItem, sneaking, sprinting, onGround,
                 velocityX, velocityY, velocityZ, health, foodLevel,
                 "SURVIVAL", false, false, false, true, true, new ArrayList<>());
        }
    }

    public static class PotionEffectData implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String type; // PotionEffectType name
        public final int duration; // Duration in ticks
        public final int amplifier; // Amplifier level
        public final boolean ambient;
        public final boolean particles;
        public final boolean icon;

        public PotionEffectData(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            this.type = type;
            this.duration = duration;
            this.amplifier = amplifier;
            this.ambient = ambient;
            this.particles = particles;
            this.icon = icon;
        }
        
        public static PotionEffectData fromBukkit(org.bukkit.potion.PotionEffect effect) {
            return new PotionEffectData(
                effect.getType().getName(),
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon()
            );
        }
    }

    public static class DeathEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        public final UUID playerId;
        public final String playerName;
        public final String deathMessage;
        public final String deathCause;
        public final long timestamp;
        public final boolean hasBed; // For games like BedWars
        public final String teamName; // Team-based games
        public final boolean eliminated; // Whether player is fully eliminated

        public DeathEvent(UUID playerId, String playerName, String deathMessage, String deathCause, 
                         boolean hasBed, String teamName, boolean eliminated) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.deathMessage = deathMessage;
            this.deathCause = deathCause;
            this.timestamp = System.currentTimeMillis();
            this.hasBed = hasBed;
            this.teamName = teamName;
            this.eliminated = eliminated;
        }
    }

    public static class BlockChange implements Serializable {
        private static final long serialVersionUID = 1L;
        public final int x, y, z;
        public final String material; // "AIR" for break, material name for place

        public BlockChange(int x, int y, int z, String material) {
            this.x = x; this.y = y; this.z = z;
            this.material = material;
        }
    }
}
