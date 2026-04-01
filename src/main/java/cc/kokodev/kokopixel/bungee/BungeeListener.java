package cc.kokodev.kokopixel.bungee;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.party.CrossServerPartyState;
import cc.kokodev.kokopixel.queue.QueueManager;
import cc.kokodev.kokopixel.replay.ReplayIndexEntry;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-side plugin message handler.
 *
 * Replay index: no longer maintained here — the proxy (BungeePlugin) is the
 * single source of truth. We receive REPLAY_INDEX_FULL on connect and
 * REPLAY_INDEX_ENTRY for incremental updates.
 *
 * Cross-server parties: proxy sends us PARTY_* events so we can keep local
 * party state in sync and handle pending queue/replay warps.
 */
public class BungeeListener implements PluginMessageListener {

    private final KokoPixel plugin;

    // Queue merge state
    private final Map<String, Integer> remoteQueueSizes = new HashMap<>();
    private final Map<String, Long> serverHeartbeats = new HashMap<>();
    private final Map<String, Map<String, Integer>> remoteQueueReports = new HashMap<>();

    // Replay index — populated by proxy, never by peer servers directly
    private final Map<UUID, ReplayIndexEntry> replayIndex = new ConcurrentHashMap<>();

    // Pending actions for players about to arrive on this server
    // playerUUID -> gameName (queue on arrival)
    private final Map<UUID, String> pendingQueue = new ConcurrentHashMap<>();
    // playerUUID -> gameId (replay on arrival)
    private final Map<UUID, UUID> pendingReplay = new ConcurrentHashMap<>();

    // Network-wide player list: updated via PLAYER_LIST messages from proxy
    private final Map<UUID, String> networkPlayerNames = new ConcurrentHashMap<>();
    private final Map<String, UUID> networkPlayerByName = new ConcurrentHashMap<>();
    private final Map<UUID, String> networkPlayerServer = new ConcurrentHashMap<>();

    private static final long HEARTBEAT_TIMEOUT_MS = 10_000;

    public BungeeListener(KokoPixel plugin) {
        this.plugin = plugin;
        startTimeoutWatcher();
        startMergeScheduler();
        // Request full replay index from proxy on startup
        requestFullIndex();
    }

    // -------------------------------------------------------------------------
    // Startup index request
    // -------------------------------------------------------------------------

    private void requestFullIndex() {
        // Slight delay so the channel is registered before we send
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            sendToProxy("REPLAY_INDEX_REQUEST", out -> out.writeUTF(plugin.getServerId())), 40L);
    }

    // -------------------------------------------------------------------------
    // Timeout watcher
    // -------------------------------------------------------------------------

    private void startTimeoutWatcher() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            serverHeartbeats.entrySet().removeIf(entry -> {
                if (now - entry.getValue() > HEARTBEAT_TIMEOUT_MS) {
                    plugin.getLogger().warning("Server " + entry.getKey() + " timed out.");
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.getMinigameManager().clearStaleOfflinePlayers());
                    remoteQueueReports.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }, 100L, 100L);
    }

    // -------------------------------------------------------------------------
    // Queue merge scheduler
    // -------------------------------------------------------------------------

    private void startMergeScheduler() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            broadcastQueueReport();
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getQueueManager().evaluateCrossServerMerge(remoteQueueReports), 40L);
        }, 1200L, 1200L);
    }

    public void broadcastQueueReport() {
        QueueManager qm = plugin.getQueueManager();
        for (String gameName : plugin.getMinigameManager().getMinigameNames()) {
            int size = qm.getQueueSize(gameName);
            sendToAll("QUEUE_REPORT", out -> {
                out.writeUTF(plugin.getServerId());
                out.writeUTF(gameName);
                out.writeInt(size);
            });
        }
    }

    public void sendMergeRequest(String targetServerId, String gameType, int incomingCount) {
        sendToAll("MERGE_INTO", out -> {
            out.writeUTF(targetServerId);
            out.writeUTF(plugin.getServerId());
            out.writeUTF(gameType);
            out.writeInt(incomingCount);
        });
    }

    // -------------------------------------------------------------------------
    // Incoming message handler
    // -------------------------------------------------------------------------

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("kokopixel:main")) return;
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String sub = in.readUTF();
        switch (sub) {

            case "HEARTBEAT": {
                String serverId = in.readUTF(); in.readInt(); in.readInt();
                serverHeartbeats.put(serverId, System.currentTimeMillis());
                break;
            }
            case "QUEUE_UPDATE": {
                String gameType = in.readUTF(); int size = in.readInt();
                remoteQueueSizes.put(gameType, size);
                plugin.getGameSelectorMenu().updateRemoteQueueSize(gameType, size);
                break;
            }
            case "QUEUE_REPORT": {
                String fromServer = in.readUTF(); String gameType = in.readUTF(); int size = in.readInt();
                remoteQueueReports.computeIfAbsent(fromServer, k -> new HashMap<>()).put(gameType, size);
                plugin.getGameSelectorMenu().updateRemoteQueueSize(gameType, size);
                break;
            }
            case "MERGE_INTO": {
                String targetServer = in.readUTF(); String destServer = in.readUTF();
                String gameType = in.readUTF(); in.readInt();
                if (targetServer.equals(plugin.getServerId()))
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.getQueueManager().dissolveQueueToServer(gameType, destServer));
                break;
            }
            case "QUEUE_REQUEST": {
                String targetGame = in.readUTF(); UUID requesterId = UUID.fromString(in.readUTF());
                Player requester = plugin.getServer().getPlayer(requesterId);
                if (requester != null && requester.isOnline())
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getQueueManager().addToQueue(requester,
                            plugin.getMinigameManager().getMinigame(targetGame)));
                break;
            }

            // ---- Replay index from proxy ----
            case "REPLAY_INDEX_FULL": {
                int count = in.readInt();
                replayIndex.clear();
                for (int i = 0; i < count; i++) {
                    UUID gameId = UUID.fromString(in.readUTF());
                    String gameType = in.readUTF();
                    long recordedAt = in.readLong();
                    long durationSec = in.readLong();
                    int participants = in.readInt();
                    String hostServer = in.readUTF();
                    replayIndex.put(gameId, new ReplayIndexEntry(
                            gameId, gameType, recordedAt, durationSec, participants, hostServer));
                }
                plugin.getLogger().info("[Replay] Received full index from proxy: " + count + " entries.");
                break;
            }
            case "REPLAY_INDEX_ENTRY": {
                UUID gameId = UUID.fromString(in.readUTF());
                String gameType = in.readUTF();
                long recordedAt = in.readLong();
                long durationSec = in.readLong();
                int participants = in.readInt();
                String hostServer = in.readUTF();
                ReplayIndexEntry entry = new ReplayIndexEntry(
                        gameId, gameType, recordedAt, durationSec, participants, hostServer);
                if (entry.isExpired()) replayIndex.remove(gameId);
                else replayIndex.put(gameId, entry);
                break;
            }

            // ---- Pending actions for arriving players ----
            case "PENDING_QUEUE": {
                UUID playerId = UUID.fromString(in.readUTF());
                in.readUTF(); // destServer (informational here)
                String gameName = in.readUTF();
                pendingQueue.put(playerId, gameName);
                break;
            }
            case "PENDING_REPLAY": {
                UUID playerId = UUID.fromString(in.readUTF());
                in.readUTF(); // destServer
                UUID gameId = UUID.fromString(in.readUTF());
                pendingReplay.put(playerId, gameId);
                break;
            }

            // ---- Cross-server party events from proxy ----
            case "PARTY_MEMBER_JOINED": {
                UUID partyId = UUID.fromString(in.readUTF());
                UUID memberUUID = UUID.fromString(in.readUTF());
                String memberName = in.readUTF();
                String memberServer = in.readUTF();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getPartyManager().onRemoteMemberJoined(partyId, memberUUID, memberName, memberServer));
                break;
            }
            case "PARTY_MEMBER_LEFT": {
                UUID partyId = UUID.fromString(in.readUTF());
                UUID memberUUID = UUID.fromString(in.readUTF());
                in.readUTF(); in.readUTF();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getPartyManager().onRemoteMemberLeft(partyId, memberUUID));
                break;
            }
            case "PARTY_DISBANDED": {
                UUID partyId = UUID.fromString(in.readUTF());
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getPartyManager().onRemotePartyDisbanded(partyId));
                break;
            }

            // ---- Network player list updates from proxy ----
            case "PLAYER_ONLINE": {
                UUID pid = UUID.fromString(in.readUTF());
                String pname = in.readUTF();
                String pserver = in.readUTF();
                networkPlayerNames.put(pid, pname);
                networkPlayerByName.put(pname.toLowerCase(), pid);
                networkPlayerServer.put(pid, pserver);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getFriendManager().setNetworkOnline(pid, pserver, pname));
                break;
            }
            case "PLAYER_OFFLINE": {
                UUID pid = UUID.fromString(in.readUTF());
                String pname = networkPlayerNames.remove(pid);
                if (pname != null) networkPlayerByName.remove(pname.toLowerCase());
                networkPlayerServer.remove(pid);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getFriendManager().setNetworkOffline(pid));
                break;
            }

            // ---- Cross-server direct message ----
            case "DIRECT_MSG": {
                UUID toId = UUID.fromString(in.readUTF());
                Player local = plugin.getServer().getPlayer(toId);
                if (local == null || !local.isOnline()) break;
                UUID fromId = UUID.fromString(in.readUTF());
                String fromName = in.readUTF();
                String dmMessage = in.readUTF();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getMsgCommand().deliverIncoming(fromId, fromName, toId, dmMessage));
                break;
            }

            // ---- Replay start (direct server-to-server, kept for compat) ----
            case "REPLAY_START": {
                String targetServer = in.readUTF();
                if (!targetServer.equals(plugin.getServerId())) break;
                UUID playerId = UUID.fromString(in.readUTF());
                UUID gameId = UUID.fromString(in.readUTF());
                plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getReplayManager().registerPendingStart(playerId, gameId));
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pending action consumers — called from PlayerListener on join
    // -------------------------------------------------------------------------

    public String consumePendingQueue(UUID playerId) { return pendingQueue.remove(playerId); }
    public UUID consumePendingReplay(UUID playerId)  { return pendingReplay.remove(playerId); }

    // -------------------------------------------------------------------------
    // Outbound helpers
    // -------------------------------------------------------------------------

    /** Push a single replay index entry to the proxy (which fans it out). */
    public void pushIndexEntry(ReplayIndexEntry entry) {
        sendToProxy("REPLAY_INDEX_UPDATE", out -> {
            out.writeUTF(plugin.getServerId());
            out.writeUTF(entry.gameId.toString());
            out.writeUTF(entry.gameType);
            out.writeLong(entry.recordedAt);
            out.writeLong(entry.durationSeconds);
            out.writeInt(entry.participantCount);
        });
    }

    /** Tell the proxy to warp all remote party members to destServer for a queue. */
    public void sendPartyWarpQueue(UUID partyId, String destServer, String gameName) {
        sendToProxy("PARTY_WARP_QUEUE", out -> {
            out.writeUTF(partyId.toString());
            out.writeUTF(destServer);
            out.writeUTF(gameName);
        });
    }

    /** Tell the proxy to warp all remote party members to destServer for a replay. */
    public void sendPartyWarpReplay(UUID partyId, String destServer, UUID gameId) {
        sendToProxy("PARTY_WARP_REPLAY", out -> {
            out.writeUTF(partyId.toString());
            out.writeUTF(destServer);
            out.writeUTF(gameId.toString());
        });
    }

    /** Notify proxy that a party was created on this server. */
    public void sendPartyCreate(UUID partyId, UUID leaderUUID, String leaderName) {
        sendToProxy("PARTY_CREATE", out -> {
            out.writeUTF(partyId.toString());
            out.writeUTF(leaderUUID.toString());
            out.writeUTF(leaderName);
            out.writeUTF(plugin.getServerId());
        });
    }

    /** Notify proxy that a player joined a party on this server. */
    public void sendPartyJoin(UUID partyId, UUID memberUUID, String memberName) {
        sendToProxy("PARTY_JOIN", out -> {
            out.writeUTF(partyId.toString());
            out.writeUTF(memberUUID.toString());
            out.writeUTF(memberName);
            out.writeUTF(plugin.getServerId());
        });
    }

    /** Notify proxy that a player left a party. */
    public void sendPartyLeave(UUID partyId, UUID memberUUID) {
        sendToProxy("PARTY_LEAVE", out -> {
            out.writeUTF(partyId.toString());
            out.writeUTF(memberUUID.toString());
        });
    }

    public void routePlayerToReplay(Player player, String hostServerId, UUID gameId) {
        sendToProxy("REPLAY_START", out -> {
            out.writeUTF(hostServerId);
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(gameId.toString());
        });
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> connectToServer(player, hostServerId), 5L);
    }

    public void connectToServer(Player p, String serverId) {
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(byteOut);
            out.writeUTF("Connect");
            out.writeUTF(serverId);
            p.sendPluginMessage(plugin, "BungeeCord", byteOut.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to connect " + p.getName() + " to " + serverId);
        }
    }

    @FunctionalInterface
    public interface DataWriter { void write(DataOutputStream out) throws Exception; }

    /** Send to all servers (via BungeeCord broadcast). */
    public void sendToAll(String subchannel, DataWriter writer) {
        Player any = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        if (any == null) return;
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(byteOut);
            out.writeUTF(subchannel);
            writer.write(out);
            plugin.getServer().sendPluginMessage(plugin, "kokopixel:main", byteOut.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send plugin message: " + e.getMessage());
        }
    }

    /** Send directly to the proxy (BungeeCord plugin). */
    public void sendToProxy(String subchannel, DataWriter writer) {
        // Plugin messages to "kokopixel:main" are received by BungeePlugin on the proxy
        sendToAll(subchannel, writer);
    }

    public void broadcastReplayIndex() { /* no-op — proxy handles fan-out now */ }

    public int getRemoteQueueSize(String gameType) { return remoteQueueSizes.getOrDefault(gameType, 0); }
    public Map<String, Map<String, Integer>> getRemoteQueueReports() { return remoteQueueReports; }
    public Map<UUID, ReplayIndexEntry> getReplayIndex() { return replayIndex; }

    // Network player list accessors
    public UUID getNetworkPlayerByName(String name) { return networkPlayerByName.get(name.toLowerCase()); }
    public Collection<String> getNetworkPlayerNames() { return networkPlayerNames.values(); }
    public String getNetworkPlayerServer(UUID id) { return networkPlayerServer.get(id); }

    /** Send a cross-server direct message via the proxy. */
    public void sendCrossServerMsg(UUID fromId, String fromName, UUID toId, String toName, String message) {
        sendToAll("DIRECT_MSG", out -> {
            out.writeUTF(toId.toString());
            out.writeUTF(fromId.toString());
            out.writeUTF(fromName);
            out.writeUTF(message);
        });
    }
}
