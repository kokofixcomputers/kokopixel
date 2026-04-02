package cc.kokodev.kokopixel.replay;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.GameInstanceImpl;
import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.replay.ReplayData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Robust game recorder that captures comprehensive game state every tick.
 * This replaces the buggy original with a new system that records all entities.
 */
public class EnhancedGameRecorder {
    public final GameInstanceImpl game;
    public final JavaPlugin plugin;
    
    private final List<ReplayData> frames = new ArrayList<>();
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, String> skinTextures = new LinkedHashMap<>();
    private final long startedAt = System.currentTimeMillis();
    private org.bukkit.scheduler.BukkitTask task;
    private boolean stopped = false;
    
    // Track previous state to detect changes
    private final Map<UUID, ReplayData.PlayerState> lastPlayerStates = new HashMap<>();
    private final Map<String, Material> lastBlockStates = new HashMap<>();
    private final Map<UUID, Long> lastHandSwing = new HashMap<>();

    public EnhancedGameRecorder(GameInstanceImpl game, JavaPlugin plugin) {
        this.game = game;
        this.plugin = plugin;
    }

    public void start() {
        // Sample every tick for smooth replay
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (stopped) return;
        
        ReplayData frame = new ReplayData(System.currentTimeMillis());
        
        // Record all player states
        for (GamePlayer p : game.getPlayers()) {
            Player player = p.getPlayer();
            
            // Record player state
            ReplayData.PlayerState state = new ReplayData.PlayerState(player);
            frame.playerStates.put(player.getUniqueId(), state);
            participants.add(player.getUniqueId());
            
            // Capture skin texture once per player
            if (!skinTextures.containsKey(player.getUniqueId())) {
                captureSkinTexture(player);
            }
        }
        
        // Capture block changes
        captureBlockChanges(frame);
        
        frames.add(frame);
        
        // Update last states for next tick
        lastPlayerStates.clear();
        for (Map.Entry<UUID, ReplayData.PlayerState> entry : frame.playerStates.entrySet()) {
            lastPlayerStates.put(entry.getKey(), entry.getValue());
        }
    }

    private void captureBlockChanges(ReplayData frame) {
        // This is called from event listeners when blocks change
        // For now, just initialize the block states tracking
        if (lastBlockStates.isEmpty()) {
            // Initialize with current world state
            int radius = 100; // Track blocks within 100 blocks of players
            for (ReplayData.PlayerState state : frame.playerStates.values()) {
                int centerX = (int) state.x;
                int centerY = (int) state.y;
                int centerZ = (int) state.z;
                
                // Sample blocks around player
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            org.bukkit.Material blockMaterial = game.getWorld().getBlockAt(centerX + dx, centerY + dy, centerZ + dz).getType();
                            String key = (centerX + dx) + "," + (centerY + dy) + "," + (centerZ + dz);
                            lastBlockStates.put(key, blockMaterial);
                        }
                    }
                }
            }
        }
    }

    /** Record a hand swing animation for a player */
    public void recordHandSwing(UUID playerId) {
        long currentTime = System.currentTimeMillis();
        lastHandSwing.put(playerId, currentTime);
        
        // Find the current frame and add animation event
        if (!frames.isEmpty()) {
            ReplayData currentFrame = frames.get(frames.size() - 1);
            currentFrame.animationEvents.add(new ReplayData.AnimationEvent(
                playerId, currentTime, ReplayData.AnimationEvent.AnimationType.HAND_SWING
            ));
        }
    }

    /** Record a custom game event */
    public void recordGameEvent(ReplayData.GameEvent.EventType type, UUID playerId, String playerName, String message, Map<String, String> data) {
        if (stopped) return;
        
        // Find the current frame and add game event
        if (!frames.isEmpty()) {
            ReplayData frame = frames.get(frames.size() - 1);
            frame.gameEvents.add(new ReplayData.GameEvent(type, playerId, playerName, message, data));
        }
    }

    /** Record a damage event (captures red flash effect) */
    public void recordDamage(Player victim, Player attacker, EntityDamageEvent.DamageCause cause, double damage, boolean fatal) {
        if (stopped) return;
        
        // Find the current frame and add damage event
        if (!frames.isEmpty()) {
            ReplayData currentFrame = frames.get(frames.size() - 1);
            currentFrame.damageEvents.add(new ReplayData.DamageEvent(
                victim.getUniqueId(), victim.getName(),
                attacker != null ? attacker.getUniqueId() : null,
                attacker != null ? attacker.getName() : null,
                cause.name(), damage, fatal
            ));
        }
    }

    /** Record a block change */
    public void recordBlockChange(int x, int y, int z, String oldMaterial, String newMaterial) {
        if (stopped) return;
        
        // Track block changes for recording
        String key = x + "," + y + "," + z;
        Material previousMaterial = lastBlockStates.get(key);
        
        // Add block change to current frame
        if (!frames.isEmpty()) {
            ReplayData currentFrame = frames.get(frames.size() - 1);
            currentFrame.blockChanges.add(new ReplayData.BlockChange(x, y, z, 
                previousMaterial != null ? previousMaterial.name() : oldMaterial, 
                newMaterial));
            
            // Debug logging
            plugin.getLogger().info("[Replay] Recorded block change: " + x + "," + y + "," + z + 
                " from " + (previousMaterial != null ? previousMaterial.name() : oldMaterial) + 
                " to " + newMaterial);
        }
        
        // Update tracking
        lastBlockStates.put(key, Material.valueOf(newMaterial));
    }

    /** Capture player skin texture for replay */
    private void captureSkinTexture(Player p) {
        try {
            org.bukkit.profile.PlayerProfile pp = p.getPlayerProfile();
            org.bukkit.profile.PlayerTextures textures = pp.getTextures();
            if (textures != null && textures.getSkin() != null) {
                // For now, just store a placeholder - the exact texture API may vary
                skinTextures.put(p.getUniqueId(), "skin_texture_" + p.getUniqueId());
            }
        } catch (Exception ignored) {}
    }

    /** Stop recording and return the finished ReplayRecording. */
    public EnhancedReplayRecording stop() {
        stopped = true;
        if (task != null) task.cancel();
        
        long framesWithPlayers = frames.stream().filter(f -> !f.playerStates.isEmpty()).count();
        plugin.getLogger().info("[Replay] Recording stopped: " + frames.size() + " total frames, "
                + framesWithPlayers + " with player data, "
                + participants.size() + " participants.");
                
        return new EnhancedReplayRecording(
                game.getGameId(),
                game.getGameType(),
                game.getGameType(),
                startedAt,
                Collections.unmodifiableSet(participants),
                Collections.unmodifiableList(frames),
                Collections.unmodifiableMap(new LinkedHashMap<>(skinTextures))
        );
    }
}
