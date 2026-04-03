package cc.kokodev.kokopixel.replay;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.GameInstanceImpl;
import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.replay.EnhancedGameRecorder;
import cc.kokodev.kokopixel.replay.EnhancedReplayRecording;
import cc.kokodev.kokopixel.replay.EnhancedReplaySession;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Enhanced ReplayManager with comprehensive event capture and robust playback.
 * Replaces the buggy original with the new system.
 */
public class EnhancedReplayManager implements Listener {

    private final KokoPixel plugin;
    private final File replayDir;

    /** gameId -> active recorder */
    private final Map<UUID, EnhancedGameRecorder> activeRecorders = new ConcurrentHashMap<>();
    /** gameId -> loaded recording (in-memory cache) */
    private final Map<UUID, EnhancedReplayRecording> recordings = new ConcurrentHashMap<>();
    /** gameId -> active playback session */
    private final Map<UUID, EnhancedReplaySession> activeSessions = new ConcurrentHashMap<>();
    /** Players waiting to start a replay on this server */
    private final Map<UUID, UUID> pendingRemoteStart = new ConcurrentHashMap<>();

    public EnhancedReplayManager(KokoPixel plugin) {
        this.plugin = plugin;
        this.replayDir = new File(plugin.getDataFolder(), "replays");
        replayDir.mkdirs();
        loadAllFromDisk();
        startExpiryTask();
        
        // Register events for comprehensive recording
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // -------------------------------------------------------------------------
    // Recording lifecycle
    // -------------------------------------------------------------------------

    public void startRecording(GameInstanceImpl game) {
        EnhancedGameRecorder recorder = new EnhancedGameRecorder(game, plugin);
        activeRecorders.put(game.getGameId(), recorder);
        recorder.start();
        plugin.getLogger().info("[Replay] Started comprehensive recording for game " + game.getGameId());
    }

    public void stopRecording(GameInstanceImpl game) {
        EnhancedGameRecorder recorder = activeRecorders.remove(game.getGameId());
        if (recorder == null) return;
        
        EnhancedReplayRecording recording = recorder.stop();
        recordings.put(recording.gameId, recording);
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File file = fileFor(recording.gameId);
                recording.saveTo(file);
                plugin.getLogger().info("[Replay] Saved comprehensive recording " + recording.gameId
                        + " (" + recording.durationSeconds() + "s, "
                        + recording.participants.size() + " players)");
                        
                // Push index entry to proxy
                if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null) {
                    ReplayIndexEntry entry = new ReplayIndexEntry(
                            recording.gameId, recording.gameType, recording.recordedAt,
                            recording.durationSeconds(), recording.participants.size(),
                            plugin.getServerId());
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.getBungeeListener().broadcastReplayIndex());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Replay] Failed to save recording " + recording.gameId, e);
            }
        });
    }

    /* Forward a block change to active recorder for that world's game. */
    public void recordBlockChange(UUID gameId, int x, int y, int z, String oldMaterial, String newMaterial, boolean cancelled) {
        // TODO: Implement entity recording once compilation issues are resolved
        EnhancedGameRecorder rec = activeRecorders.get(gameId);
        if (rec != null) rec.recordBlockChange(x, y, z, oldMaterial, newMaterial, cancelled);
    }
    
    // -------------------------------------------------------------------------
    // Event listeners for comprehensive recording
    // -------------------------------------------------------------------------
    
    private EnhancedGameRecorder findRecorderForPlayer(Player player) {
        for (EnhancedGameRecorder recorder : activeRecorders.values()) {
            if (recorder.game.getPlayers().stream().anyMatch(p -> p.getUniqueId().equals(player.getUniqueId()))) {
                return recorder;
            }
        }
        return null;
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        
        EnhancedGameRecorder recorder = findRecorderForPlayer(player);
        if (recorder == null) return;
        
        // Determine if player is eliminated (no bed in BedWars, etc.)
        boolean eliminated = true; // Default to eliminated, games can override
        String deathMessage = "died";
        
        // Try to get more specific death information
        EntityDamageEvent lastDamage = player.getLastDamageCause();
        if (lastDamage != null) {
            deathMessage = buildDeathMessage(lastDamage);
        }
        
        // Record the death event
        Map<String, Object> deathData = new HashMap<>();
        deathData.put("deathMessage", event.getDeathMessage());
        deathData.put("drops", event.getDrops().size());
        deathData.put("droppedExp", event.getDroppedExp());
        
        // Convert death data to Map<String, String>
        Map<String, String> deathDataString = new HashMap<>();
        deathDataString.put("deathMessage", event.getDeathMessage() != null ? event.getDeathMessage() : "");
        deathDataString.put("drops", String.valueOf(event.getDrops().size()));
        deathDataString.put("droppedExp", String.valueOf(event.getDroppedExp()));
        
        recorder.recordGameEvent(ReplayData.GameEvent.EventType.PLAYER_DEATH, 
            player.getUniqueId(), player.getName(), deathMessage, deathDataString);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        
        // Find which game this player was in
        EnhancedGameRecorder recorder = findRecorderForPlayer(victim);
        if (recorder == null) return;
        
        // Determine attacker
        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent edbe = (EntityDamageByEntityEvent) event;
            if (edbe.getDamager() instanceof Player) {
                attacker = (Player) edbe.getDamager();
            }
        }
        
        // Check if this damage is fatal
        boolean fatal = victim.getHealth() - event.getFinalDamage() <= 0;
        
        // Record the damage event (this captures the red flash effect)
        recorder.recordDamage(victim, attacker, event.getCause(), event.getFinalDamage(), fatal);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Record hand swing for ANY interaction (left click air or block)
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_AIR || 
            event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK ||
            event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR ||
            event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            
            EnhancedGameRecorder recorder = findRecorderForPlayer(event.getPlayer());
            if (recorder != null) {
                recorder.recordHandSwing(event.getPlayer().getUniqueId());
            }
        }
        
        // Also record block placement animations
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && 
            event.getClickedBlock() != null && event.getClickedBlock().getType().isBlock()) {
            
            EnhancedGameRecorder recorder = findRecorderForPlayer(event.getPlayer());
            if (recorder != null) {
                // Record block placement animation
                recorder.recordHandSwing(event.getPlayer().getUniqueId());
            }
        }
    }

    private String buildDeathMessage(EntityDamageEvent damageEvent) {
        if (damageEvent instanceof EntityDamageByEntityEvent edbe) {
            org.bukkit.entity.Entity damager = edbe.getDamager();
            if (damager instanceof org.bukkit.entity.Arrow arrow && arrow.getShooter() instanceof Player killer)
                return "was shot by " + killer.getName();
            if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player killer)
                return "was blown up by " + killer.getName();
            if (damager instanceof org.bukkit.entity.Fireball fireball && fireball.getShooter() instanceof Player killer)
                return "was fireballed by " + killer.getName();
            if (damager instanceof Player killer)
                return "was killed by " + killer.getName();
        }

        return switch (damageEvent.getCause()) {
            case FALL -> "fell to their death";
            case VOID -> "fell into the void";
            case DROWNING -> "drowned";
            case FIRE, FIRE_TICK -> "burned to death";
            case LAVA -> "tried to swim in lava";
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "was blown up";
            case STARVATION -> "starved to death";
            default -> "died";
        };
    }


    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

    public void startOrJoinSession(Player viewer, UUID gameId) {
        EnhancedReplayRecording recording = recordings.get(gameId);
        if (recording == null) { 
            viewer.sendMessage("§c[Replay] Recording not found."); 
            return; 
        }
        if (recording.isExpired()) { 
            viewer.sendMessage("§c[Replay] This recording has expired."); 
            return; 
        }

        // If a session already exists for this recording, just join it
        EnhancedReplaySession existing = activeSessions.get(gameId);
        if (existing != null && !existing.isFinished()) {
            addViewersFromParty(viewer, existing);
            return;
        }

        // Clone the template world
        cc.kokodev.kokopixel.minigames.Minigame mg = plugin.getMinigameManager().getMinigame(recording.minigameName);
        if (mg == null) { 
            viewer.sendMessage("§c[Replay] Minigame '" + recording.minigameName + "' is not loaded."); 
            return; 
        }

        viewer.sendMessage("§7[§dReplay§7] §eLoading comprehensive replay world...");
        plugin.getWorldManager().createGameWorldAsync(mg, world -> {
            if (world == null) { 
                viewer.sendMessage("§c[Replay] Could not create replay world."); 
                return; 
            }
            
            // Check again — another session may have started while we were loading
            EnhancedReplaySession concurrent = activeSessions.get(gameId);
            if (concurrent != null && !concurrent.isFinished()) {
                plugin.getWorldManager().deleteWorld(world); // discard the extra copy
                addViewersFromParty(viewer, concurrent);
                return;
            }
            
            EnhancedReplaySession session = new EnhancedReplaySession(plugin, recording, world);
            activeSessions.put(gameId, session);
            addViewersFromParty(viewer, session);
            session.start();
        });
    }

    private void addViewersFromParty(Player viewer, EnhancedReplaySession session) {
        plugin.getPartyManager().getParty(viewer).ifPresentOrElse(party -> {
            if (party.isLeader(viewer)) {
                // Add local members
                for (Player member : party.getOnlineMembers()) {
                    session.addViewer(member);
                }
                // Warp remote members to this server
                plugin.getPartyManager().warpPartyForReplay(
                        party, plugin.getServerId(), session.getRecordingId());
            } else {
                session.addViewer(viewer);
            }
        }, () -> session.addViewer(viewer));
    }

    /** Called by ReplaySession when it finishes. */
    public void onSessionEnd(EnhancedReplaySession session) {
        activeSessions.values().remove(session);
    }

    /** Returns the active session a player is currently watching, if any. */
    public Optional<EnhancedReplaySession> getSessionFor(UUID playerId) {
        return activeSessions.values().stream()
                .filter(s -> s.hasViewer(playerId))
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // Querying
    // -------------------------------------------------------------------------

    /** Returns all non-expired recordings that the given player participated in. */
    public List<EnhancedReplayRecording> getRecordingsFor(UUID playerId) {
        List<EnhancedReplayRecording> result = new ArrayList<>();
        for (EnhancedReplayRecording r : recordings.values()) {
            if (!r.isExpired() && r.participants.contains(playerId)) result.add(r);
        }
        result.sort(Comparator.comparingLong((EnhancedReplayRecording r) -> r.recordedAt).reversed());
        return result;
    }

    /** Legacy compatibility method - returns old-style recordings */
    public List<ReplayRecording> getLegacyRecordingsFor(UUID playerId) {
        // Convert enhanced recordings to legacy format for compatibility
        List<ReplayRecording> result = new ArrayList<>();
        for (EnhancedReplayRecording r : getRecordingsFor(playerId)) {
            // This would need proper conversion - for now return empty list
            // result.add(convertToLegacy(r));
        }
        return result;
    }

    public Optional<EnhancedReplayRecording> getRecording(UUID gameId) {
        return Optional.ofNullable(recordings.get(gameId));
    }

    /** Admin GUI compatibility method */
    public List<ReplayIndexEntry> getAllKnownReplays(Map<String, List<ReplayIndexEntry>> remoteIndex) {
        List<ReplayIndexEntry> all = new ArrayList<>();
        
        // Add local recordings
        for (EnhancedReplayRecording r : recordings.values()) {
            if (!r.isExpired()) {
                all.add(new ReplayIndexEntry(
                    r.gameId, r.gameType, r.recordedAt,
                    r.durationSeconds(), r.participants.size(),
                    plugin.getServerId()));
            }
        }
        
        // Add remote recordings
        for (List<ReplayIndexEntry> serverList : remoteIndex.values()) {
            all.addAll(serverList);
        }
        
        all.sort(Comparator.comparingLong((ReplayIndexEntry e) -> e.recordedAt).reversed());
        return all;
    }

    // -------------------------------------------------------------------------
    // Disk I/O
    // -------------------------------------------------------------------------

    private void loadAllFromDisk() {
        File[] files = replayDir.listFiles((d, n) -> n.endsWith(".replay"));
        if (files == null) return;
        int loaded = 0, expired = 0;
        for (File f : files) {
            try {
                EnhancedReplayRecording r = EnhancedReplayRecording.loadFrom(f);
                if (r.isExpired()) {
                    f.delete();
                    expired++;
                } else {
                    recordings.put(r.gameId, r);
                    loaded++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Could not load " + f.getName() + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("[Replay] Loaded " + loaded + " comprehensive recordings, deleted " + expired + " expired.");
    }

    private File fileFor(UUID gameId) {
        return new File(replayDir, gameId.toString() + ".replay");
    }

    // -------------------------------------------------------------------------
    // Remote-start handshake
    // -------------------------------------------------------------------------

    public void registerPendingStart(UUID playerId, UUID gameId) {
        pendingRemoteStart.put(playerId, gameId);
    }

    public void checkPendingStart(Player player) {
        UUID gameId = pendingRemoteStart.remove(player.getUniqueId());
        if (gameId == null) return;
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> startOrJoinSession(player, gameId), 20L);
    }

    /**
     * Check if a world is a replay world (used for damage/explosion prevention)
     */
    public boolean isReplayWorld(String worldName) {
        return activeSessions.values().stream()
                .anyMatch(session -> session.getReplayWorld().getName().equals(worldName));
    }

    // -------------------------------------------------------------------------
    // Expiry — runs every 10 minutes, deletes recordings older than 6 hours
    // -------------------------------------------------------------------------

    private void startExpiryTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            Iterator<Map.Entry<UUID, EnhancedReplayRecording>> it = recordings.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, EnhancedReplayRecording> entry = it.next();
                if (entry.getValue().isExpired()) {
                    fileFor(entry.getKey()).delete();
                    it.remove();
                    plugin.getLogger().info("[Replay] Expired comprehensive recording " + entry.getKey());
                }
            }
        }, 12000L, 12000L);
    }
}
