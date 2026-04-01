package cc.kokodev.kokopixel.replay;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.GameInstanceImpl;
import cc.kokodev.kokopixel.minigames.Minigame;
import org.bukkit.World;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Central manager for replay recordings.
 *
 * - Starts a GameRecorder when a game begins.
 * - Saves the recording to disk when the game ends.
 * - Loads existing recordings on startup.
 * - Runs a periodic expiry check; recordings older than 6 hours are deleted.
 *   The expiry is based on the recordedAt timestamp inside the file, so
 *   server restarts do NOT reset the 6-hour clock.
 */
public class ReplayManager {

    private final KokoPixel plugin;
    private final File replayDir;

    /** gameId -> active recorder */
    private final Map<UUID, GameRecorder> activeRecorders = new ConcurrentHashMap<>();
    /** gameId -> loaded recording (in-memory cache) */
    private final Map<UUID, ReplayRecording> recordings = new ConcurrentHashMap<>();
    /** gameId -> active playback session */
    private final Map<UUID, ReplaySession> activeSessions = new ConcurrentHashMap<>();
    /**
     * Players waiting to start a replay on this server after being routed here
     * from another server. playerUUID -> gameId. Consumed on PlayerJoinEvent.
     */
    private final Map<UUID, UUID> pendingRemoteStart = new ConcurrentHashMap<>();

    public ReplayManager(KokoPixel plugin) {
        this.plugin = plugin;
        this.replayDir = new File(plugin.getDataFolder(), "replays");
        replayDir.mkdirs();
        loadAllFromDisk();
        startExpiryTask();
    }

    // -------------------------------------------------------------------------
    // Recording lifecycle
    // -------------------------------------------------------------------------

    public void startRecording(GameInstanceImpl game) {
        GameRecorder recorder = new GameRecorder(game, plugin);
        activeRecorders.put(game.getGameId(), recorder);
        recorder.start();
        plugin.getLogger().info("[Replay] Started recording game " + game.getGameId());
    }

    public void stopRecording(GameInstanceImpl game) {
        GameRecorder recorder = activeRecorders.remove(game.getGameId());
        if (recorder == null) return;

        // Save async so we don't stall the main thread
        ReplayRecording recording = recorder.stop();
        recordings.put(recording.gameId, recording);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File file = fileFor(recording.gameId);
                recording.saveTo(file);
                plugin.getLogger().info("[Replay] Saved recording " + recording.gameId
                        + " (" + recording.durationSeconds() + "s, "
                        + recording.participants.size() + " players)");
                // Push index entry to proxy so all servers learn about it immediately
                if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null) {
                    ReplayIndexEntry entry = new ReplayIndexEntry(
                            recording.gameId, recording.gameType, recording.recordedAt,
                            recording.durationSeconds(), recording.participants.size(),
                            plugin.getServerId());
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.getBungeeListener().pushIndexEntry(entry));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Replay] Failed to save recording", e);
            }
        });
    }

    /** Forward a block change to the active recorder for that world's game. */
    public void recordBlockChange(UUID gameId, int x, int y, int z, String material) {
        GameRecorder rec = activeRecorders.get(gameId);
        if (rec != null) rec.recordBlockChange(x, y, z, material);
    }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

    /**
     * Starts or joins a replay session for the given recording.
     * Clones the template world, puts the viewer (and their party) into spectator.
     */
    public void startOrJoinSession(org.bukkit.entity.Player viewer, UUID gameId) {
        ReplayRecording recording = recordings.get(gameId);
        if (recording == null) { viewer.sendMessage("§c[Replay] Recording not found."); return; }
        if (recording.isExpired()) { viewer.sendMessage("§c[Replay] This recording has expired."); return; }

        // If a session already exists for this recording, just join it
        ReplaySession existing = activeSessions.get(gameId);
        if (existing != null && !existing.isFinished()) {
            addViewersFromParty(viewer, existing);
            return;
        }

        // Clone the template world
        Minigame mg = plugin.getMinigameManager().getMinigame(recording.minigameName);
        if (mg == null) { viewer.sendMessage("§c[Replay] Minigame '" + recording.minigameName + "' is not loaded."); return; }

        World world = plugin.getWorldManager().createGameWorld(mg);
        if (world == null) { viewer.sendMessage("§c[Replay] Could not create replay world."); return; }

        ReplaySession session = new ReplaySession(plugin, recording, world);
        activeSessions.put(gameId, session);
        addViewersFromParty(viewer, session);
        session.start();
    }

    private void addViewersFromParty(org.bukkit.entity.Player viewer, ReplaySession session) {
        plugin.getPartyManager().getParty(viewer).ifPresentOrElse(party -> {
            if (party.isLeader(viewer)) {
                // Add local members
                for (org.bukkit.entity.Player member : party.getOnlineMembers()) {
                    session.addViewer(member);
                }
                // Warp remote members to this server (or to the replay host if remote)
                plugin.getPartyManager().warpPartyForReplay(
                        party, plugin.getServerId(), session.getRecordingId());
            } else {
                session.addViewer(viewer);
            }
        }, () -> session.addViewer(viewer));
    }

    /** Called by ReplaySession when it finishes. */
    public void onSessionEnd(ReplaySession session) {
        activeSessions.values().remove(session);
    }

    /** Returns the active session a player is currently watching, if any. */
    public Optional<ReplaySession> getSessionFor(UUID playerId) {
        return activeSessions.values().stream()
                .filter(s -> s.hasViewer(playerId))
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // Querying
    // -------------------------------------------------------------------------

    /** Returns all non-expired recordings that the given player participated in. */
    public List<ReplayRecording> getRecordingsFor(UUID playerId) {
        List<ReplayRecording> result = new ArrayList<>();
        for (ReplayRecording r : recordings.values()) {
            if (!r.isExpired() && r.participants.contains(playerId)) result.add(r);
        }
        result.sort(Comparator.comparingLong((ReplayRecording r) -> r.recordedAt).reversed());
        return result;
    }

    public Optional<ReplayRecording> getRecording(UUID gameId) {
        return Optional.ofNullable(recordings.get(gameId));
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
                ReplayRecording r = ReplayRecording.loadFrom(f);
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
        plugin.getLogger().info("[Replay] Loaded " + loaded + " recordings, deleted " + expired + " expired.");
    }

    private File fileFor(UUID gameId) {
        return new File(replayDir, gameId.toString() + ".replay");
    }

    // -------------------------------------------------------------------------
    // Network index — lightweight metadata shared across servers
    // -------------------------------------------------------------------------

    /** Builds index entries for all local non-expired recordings. */
    public List<ReplayIndexEntry> buildLocalIndex() {
        List<ReplayIndexEntry> entries = new ArrayList<>();
        for (ReplayRecording r : recordings.values()) {
            if (!r.isExpired()) {
                entries.add(new ReplayIndexEntry(
                        r.gameId, r.gameType, r.recordedAt,
                        r.durationSeconds(), r.participants.size(),
                        plugin.getServerId()));
            }
        }
        return entries;
    }

    /**
     * Returns all known replay index entries: local ones plus whatever
     * the proxy has told us about. Sorted newest-first.
     * The remoteIndex parameter is now the proxy-sourced map from BungeeListener.
     */
    public List<ReplayIndexEntry> getAllKnownReplays(Map<String, List<ReplayIndexEntry>> remoteIndex) {
        // If bungee is enabled, use the authoritative proxy index
        if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null) {
            List<ReplayIndexEntry> all = new ArrayList<>(
                    plugin.getBungeeListener().getReplayIndex().values());
            all.removeIf(ReplayIndexEntry::isExpired);
            all.sort(Comparator.comparingLong((ReplayIndexEntry e) -> e.recordedAt).reversed());
            return all;
        }
        // Standalone (no bungee) — just local
        List<ReplayIndexEntry> all = new ArrayList<>(buildLocalIndex());
        all.sort(Comparator.comparingLong((ReplayIndexEntry e) -> e.recordedAt).reversed());
        return all;
    }

    // -------------------------------------------------------------------------
    // Remote-start handshake
    // -------------------------------------------------------------------------

    /**
     * Called when this server receives a REPLAY_START message from another server.
     * Registers the player as pending so when they connect we start the session.
     */
    public void registerPendingStart(UUID playerId, UUID gameId) {
        pendingRemoteStart.put(playerId, gameId);
    }

    /**
     * Called from PlayerJoinEvent. If this player has a pending replay start,
     * kick it off after a short delay (let the player fully load in first).
     */
    public void checkPendingStart(org.bukkit.entity.Player player) {
        UUID gameId = pendingRemoteStart.remove(player.getUniqueId());
        if (gameId == null) return;
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> startOrJoinSession(player, gameId), 20L);
    }

    // -------------------------------------------------------------------------
    // Expiry — runs every 10 minutes, deletes recordings older than 6 hours
    // -------------------------------------------------------------------------

    private void startExpiryTask() {
        // 12000 ticks = 10 minutes
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            Iterator<Map.Entry<UUID, ReplayRecording>> it = recordings.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, ReplayRecording> entry = it.next();
                if (entry.getValue().isExpired()) {
                    fileFor(entry.getKey()).delete();
                    it.remove();
                    plugin.getLogger().info("[Replay] Expired recording " + entry.getKey());
                }
            }
        }, 12000L, 12000L);
    }
}
