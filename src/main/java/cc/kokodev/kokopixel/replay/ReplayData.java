package cc.kokodev.kokopixel.replay;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.io.Serializable;
import java.util.*;

/**
 * Comprehensive replay data structure that captures everything needed for accurate replay.
 * This replaces the buggy original system with a more robust approach.
 */
public class ReplayData implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public final long timestamp;
    public final Map<UUID, PlayerState> playerStates = new HashMap<>();
    public final List<BlockChange> blockChanges = new ArrayList<>();
    public final List<GameEvent> gameEvents = new ArrayList<>();
    public final List<DamageEvent> damageEvents = new ArrayList<>();
    public final List<AnimationEvent> animationEvents = new ArrayList<>();
    public final List<EntityState> entityStates = new ArrayList<>();
    
    public ReplayData(long timestamp) {
        this.timestamp = timestamp;
    }
    
    /**
     * Complete player state snapshot for this tick.
     * Captures everything needed to accurately recreate player's state.
     */
    public static class PlayerState implements Serializable {
        private static final long serialVersionUID = 1L;
        
        // Basic info
        public final String name;
        public final UUID playerId;
        
        // Position and movement
        public final double x, y, z;
        public final float yaw, pitch;
        public final double velocityX, velocityY, velocityZ;
        public final boolean onGround;
        
        // Health and stats
        public final double health;
        public final int foodLevel;
        public final int fireTicks;
        public final int airTicks;
        
        // Game mode and abilities
        public final String gameMode;
        public final boolean flying, gliding;
        public final boolean sneaking, sprinting, swimming, sleeping;
        
        // Combat state
        public final int hurtTime, hurtResistantTime;
        public final boolean recentlyHit;
        
        // Inventory and equipment
        public final ItemStackData helmet, chestplate, leggings, boots;
        public final ItemStackData mainHand, offHand;
        
        // Effects and appearance
        public final List<PotionEffectData> potionEffects;
        public final boolean invisible;
        
        public PlayerState(Player player) {
            this.name = player.getName();
            this.playerId = player.getUniqueId();
            
            // Position and movement
            this.x = player.getLocation().getX();
            this.y = player.getLocation().getY();
            this.z = player.getLocation().getZ();
            this.yaw = player.getLocation().getYaw();
            this.pitch = player.getLocation().getPitch();
            this.velocityX = player.getVelocity().getX();
            this.velocityY = player.getVelocity().getY();
            this.velocityZ = player.getVelocity().getZ();
            this.onGround = player.isOnGround();
            
            // Health and stats
            this.health = player.getHealth();
            this.foodLevel = player.getFoodLevel();
            this.fireTicks = player.getFireTicks();
            this.airTicks = player.getRemainingAir();
            
            // Game mode and abilities
            this.gameMode = player.getGameMode().name();
            this.flying = player.isFlying();
            this.gliding = player.isGliding();
            this.sneaking = player.isSneaking();
            this.sprinting = player.isSprinting();
            this.swimming = player.isSwimming();
            this.sleeping = player.isSleeping();
            
            // Combat state
            this.hurtTime = player.getNoDamageTicks();
            this.hurtResistantTime = player.getMaximumNoDamageTicks();
            this.recentlyHit = player.getLastDamageCause() != null;
            
            // Inventory and equipment
            this.helmet = new ItemStackData(player.getInventory().getHelmet());
            this.chestplate = new ItemStackData(player.getInventory().getChestplate());
            this.leggings = new ItemStackData(player.getInventory().getLeggings());
            this.boots = new ItemStackData(player.getInventory().getBoots());
            this.mainHand = new ItemStackData(player.getInventory().getItemInMainHand());
            this.offHand = new ItemStackData(player.getInventory().getItemInOffHand());
            
            // Effects and appearance
            this.potionEffects = new ArrayList<>();
            for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                this.potionEffects.add(new PotionEffectData(
                    effect.getType().getName(),
                    effect.getDuration(),
                    effect.getAmplifier()
                ));
            }
            this.invisible = player.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        }
    }
    
    /**
     * Equipment data with all necessary metadata.
     */
    public static class ItemStackData implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String type;
        public final int amount;
        public final Map<String, Object> metadata;
        
        public ItemStackData(org.bukkit.inventory.ItemStack item) {
            if (item == null) {
                this.type = "AIR";
                this.amount = 0;
                this.metadata = new HashMap<>();
            } else {
                this.type = item.getType().name();
                this.amount = item.getAmount();
                this.metadata = item.serialize();
            }
        }
    }
    
    /**
     * Potion effect data for replay.
     */
    public static class PotionEffectData implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String type;
        public final int duration;
        public final int amplifier;
        
        public PotionEffectData(String type, int duration, int amplifier) {
            this.type = type;
            this.duration = duration;
            this.amplifier = amplifier;
        }
    }
    
    /**
     * Block change event for replay.
     */
    public static class BlockChange implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final int x, y, z;
        public final String oldMaterial, newMaterial;
        public final boolean cancelled;
        
        public BlockChange(int x, int y, int z, String oldMaterial, String newMaterial, boolean cancelled) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldMaterial = oldMaterial;
            this.newMaterial = newMaterial;
            this.cancelled = cancelled;
        }
    }
    
    /**
     * Custom game event for replay.
     */
    public static class GameEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final EventType type;
        public final UUID playerId;
        public final String playerName;
        public final String message;
        public final Map<String, String> data;
        
        public GameEvent(EventType type, UUID playerId, String playerName, String message, Map<String, String> data) {
            this.type = type;
            this.playerId = playerId;
            this.playerName = playerName;
            this.message = message;
            this.data = data != null ? data : new HashMap<>();
        }
        
        public enum EventType {
            GAME_START,
            GAME_END,
            BED_DESTROYED,
            TEAM_ELIMINATED,
            PLAYER_DEATH,
            PLAYER_RESPAWN,
            CUSTOM_EVENT
        }
    }
    
    /**
     * Damage event for replay (captures red flash effect).
     */
    public static class DamageEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final UUID victimId;
        public final String victimName;
        public final UUID attackerId;
        public final String attackerName;
        public final String damageCause;
        public final double damage;
        public final boolean fatal;
        public final double victimX, victimY, victimZ;
        
        public DamageEvent(UUID victimId, String victimName, UUID attackerId, String attackerName, 
                           String damageCause, double damage, boolean fatal) {
            this.victimId = victimId;
            this.victimName = victimName;
            this.attackerId = attackerId;
            this.attackerName = attackerName;
            this.damageCause = damageCause;
            this.damage = damage;
            this.fatal = fatal;
            this.victimX = 0.0; // Default values
            this.victimY = 0.0;
            this.victimZ = 0.0;
        }
    }
    
    /**
     * Animation event for replay.
     */
    public static class AnimationEvent implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final UUID playerId;
        public final long timestamp;
        public final AnimationType type;
        public final float yaw; // For head turns, etc.
        
        public AnimationEvent(UUID playerId, long timestamp, AnimationType type) {
            this(playerId, timestamp, type, 0f);
        }
        
        public AnimationEvent(UUID playerId, long timestamp, AnimationType type, float yaw) {
            this.playerId = playerId;
            this.timestamp = timestamp;
            this.type = type;
            this.yaw = yaw;
        }
        
        public enum AnimationType {
            HAND_SWING,
            HEAD_TURN,
            HURT_ANIMATION,
            CRITICAL_HIT,
            BLOCK_BREAK,
            BLOCK_PLACE
        }
    }
    
    /**
     * Complete entity state snapshot for this tick.
     * Captures all entities in the world for accurate replay.
     */
    public static class EntityState implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final UUID entityId;
        public final String entityType; // PLAYER, ITEM, ARROW, TNT, etc.
        public final double x, y, z;
        public final float yaw, pitch;
        public final double velocityX, velocityY, velocityZ;
        public final String material; // For falling blocks, items
        public final int customModelData; // For custom entity models
        public final Map<String, Object> metadata; // Additional entity data
        
        public EntityState(UUID entityId, String entityType, double x, double y, double z, 
                           float yaw, float pitch, double velocityX, double velocityY, double velocityZ,
                           String material, int customModelData, Map<String, Object> metadata) {
            this.entityId = entityId;
            this.entityType = entityType;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.material = material;
            this.customModelData = customModelData;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        public enum EntityType {
            PLAYER,
            ITEM,
            ARROW,
            SNOWBALL,
            FIREBALL,
            PRIMED_TNT,
            FALLING_BLOCK,
            EXPERIENCE_ORB,
            ARMOR_STAND,
            ANY_ENTITY // For generic entity tracking
        }
    }
}
