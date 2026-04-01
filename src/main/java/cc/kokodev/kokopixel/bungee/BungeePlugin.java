package cc.kokodev.kokopixel.bungee;

import cc.kokodev.kokopixel.replay.ReplayIndexEntry;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BungeeCord-side KokoPixel plugin.
 *
 * Acts as the single source of truth for:
 *  - Replay index (gameId -> ReplayIndexEntry) — Paper servers push updates here;
 *    new servers request the full index on connect so they never desync.
 *  - Cross-server parties — tracks which server each party member is on so the
 *    proxy can route remote members when the leader queues or starts a replay.
 */
public class BungeePlugin extends Plugin implements Listener {

    private static final String CHANNEL = "kokopixel:main";
    private static BungeePlugin instance;

    // serverId -> basic server stats
    private final Map<String, BungeeServerInfo> servers = new ConcurrentHashMap<>();

    // ---- Replay index (canonical, owned by proxy) ----
    // gameId -> entry
    private final Map<UUID, ReplayIndexEntry> replayIndex = new ConcurrentHashMap<>();

    // ---- Cross-server parties ----
    // partyId -> party
    private final Map<UUID, CrossServerParty> parties = new ConcurrentHashMap<>();
    // memberUUID -> partyId
    private final Map<UUID, UUID> playerParty = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("KokoPixel BungeeCord proxy enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("KokoPixel BungeeCord proxy disabled!");
    }

    // =========================================================================
    // Player connect/disconnect — update party member locations
    // =========================================================================

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        String serverId = event.getServer().getInfo().getName();

        // Update party member location
        UUID partyId = playerParty.get(uuid);
        if (partyId != null) {
            CrossServerParty party = parties.get(partyId);
            if (party != null) party.memberServers.put(uuid, serverId);
        }

        // Broadcast player online to all servers (friend notifications + /msg)
        broadcastToAll("PLAYER_ONLINE", out -> {
            out.writeUTF(uuid.toString());
            out.writeUTF(name);
            out.writeUTF(serverId);
        });

        // Send full replay index to the newly connected server so it's never stale
        sendFullIndexToServer(serverId);
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // Broadcast player offline to all servers
        broadcastToAll("PLAYER_OFFLINE", out -> out.writeUTF(uuid.toString()));

        UUID partyId = playerParty.get(uuid);
        if (partyId != null) {
            CrossServerParty party = parties.get(partyId);
            if (party != null) {
                // If leader disconnected, disband
                if (party.isLeader(uuid)) {
                    disbandParty(partyId);
                } else {
                    party.memberServers.remove(uuid);
                    party.memberNames.remove(uuid);
                    playerParty.remove(uuid);
                }
            }
        }
    }

    // =========================================================================
    // Plugin message handler
    // =========================================================================

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equals(CHANNEL)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            String sub = in.readUTF();

            switch (sub) {

                // ---- Server heartbeat ----
                case "HEARTBEAT": {
                    String serverId = in.readUTF();
                    int players = in.readInt();
                    int queued  = in.readInt();
                    BungeeServerInfo info = servers.computeIfAbsent(serverId, BungeeServerInfo::new);
                    info.playerCount   = players;
                    info.queueCount    = queued;
                    info.lastHeartbeat = System.currentTimeMillis();
                    break;
                }

                // ---- Replay index: a server is pushing one entry ----
                case "REPLAY_INDEX_UPDATE": {
                    String fromServer  = in.readUTF();
                    UUID   gameId      = UUID.fromString(in.readUTF());
                    String gameType    = in.readUTF();
                    long   recordedAt  = in.readLong();
                    long   durationSec = in.readLong();
                    int    participants= in.readInt();
                    ReplayIndexEntry entry = new ReplayIndexEntry(
                            gameId, gameType, recordedAt, durationSec, participants, fromServer);
                    if (entry.isExpired()) {
                        replayIndex.remove(gameId);
                    } else {
                        replayIndex.put(gameId, entry);
                    }
                    // Broadcast the updated entry to all other servers
                    broadcastIndexEntry(entry, fromServer);
                    break;
                }

                // ---- A server is requesting the full index (e.g. on startup) ----
                case "REPLAY_INDEX_REQUEST": {
                    String requestingServer = in.readUTF();
                    sendFullIndexToServer(requestingServer);
                    break;
                }

                // ---- Cross-server party: create ----
                case "PARTY_CREATE": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    UUID   leaderUUID = UUID.fromString(in.readUTF());
                    String leaderName = in.readUTF();
                    String fromServer = in.readUTF();
                    CrossServerParty party = new CrossServerParty(partyId, leaderUUID, leaderName, fromServer);
                    parties.put(partyId, party);
                    playerParty.put(leaderUUID, partyId);
                    break;
                }

                // ---- Cross-server party: member joined ----
                case "PARTY_JOIN": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    UUID   memberUUID = UUID.fromString(in.readUTF());
                    String memberName = in.readUTF();
                    String fromServer = in.readUTF();
                    CrossServerParty party = parties.get(partyId);
                    if (party != null) {
                        party.memberServers.put(memberUUID, fromServer);
                        party.memberNames.put(memberUUID, memberName);
                        playerParty.put(memberUUID, partyId);
                        // Notify all party members on other servers
                        broadcastPartyEvent("PARTY_MEMBER_JOINED", partyId, memberUUID, memberName, fromServer);
                    }
                    break;
                }

                // ---- Cross-server party: member left / disband ----
                case "PARTY_LEAVE": {
                    UUID partyId    = UUID.fromString(in.readUTF());
                    UUID memberUUID = UUID.fromString(in.readUTF());
                    CrossServerParty party = parties.get(partyId);
                    if (party != null) {
                        if (party.isLeader(memberUUID)) {
                            disbandParty(partyId);
                        } else {
                            party.memberServers.remove(memberUUID);
                            party.memberNames.remove(memberUUID);
                            playerParty.remove(memberUUID);
                            broadcastPartyEvent("PARTY_MEMBER_LEFT", partyId, memberUUID, "", "");
                        }
                    }
                    break;
                }

                // ---- Leader is queuing — route all remote members to the leader's server ----
                case "PARTY_WARP_QUEUE": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    String destServer = in.readUTF(); // leader's server
                    String gameName   = in.readUTF();
                    CrossServerParty party = parties.get(partyId);
                    if (party == null) break;
                    for (Map.Entry<UUID, String> entry : party.memberServers.entrySet()) {
                        if (entry.getKey().equals(party.leaderUUID)) continue;
                        if (!entry.getValue().equals(destServer)) {
                            // Send QUEUE_REQUEST ahead, then connect the player
                            sendToServer(entry.getValue(), "PENDING_QUEUE", out -> {
                                out.writeUTF(entry.getKey().toString());
                                out.writeUTF(destServer);
                                out.writeUTF(gameName);
                            });
                            connectPlayer(entry.getKey(), destServer);
                        }
                    }
                    break;
                }

                // ---- Leader is starting a replay — route all remote members ----
                case "PARTY_WARP_REPLAY": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    String destServer = in.readUTF();
                    UUID   gameId     = UUID.fromString(in.readUTF());
                    CrossServerParty party = parties.get(partyId);
                    if (party == null) break;
                    for (Map.Entry<UUID, String> entry : party.memberServers.entrySet()) {
                        if (entry.getKey().equals(party.leaderUUID)) continue;
                        if (!entry.getValue().equals(destServer)) {
                            sendToServer(entry.getValue(), "PENDING_REPLAY", out -> {
                                out.writeUTF(entry.getKey().toString());
                                out.writeUTF(destServer);
                                out.writeUTF(gameId.toString());
                            });
                            connectPlayer(entry.getKey(), destServer);
                        }
                    }
                    break;
                }

                // ---- BungeeCord Connect passthrough ----
                case "CONNECT": {
                    String targetServer = in.readUTF();
                    if (event.getReceiver() instanceof ProxiedPlayer p)
                        p.connect(getProxy().getServerInfo(targetServer));
                    break;
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error processing plugin message: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void disbandParty(UUID partyId) {
        CrossServerParty party = parties.remove(partyId);
        if (party == null) return;
        // Notify all servers that have party members
        Set<String> affectedServers = new HashSet<>(party.memberServers.values());
        for (String serverId : affectedServers) {
            sendToServer(serverId, "PARTY_DISBANDED", out -> out.writeUTF(partyId.toString()));
        }
        party.memberServers.keySet().forEach(playerParty::remove);
    }

    private void broadcastPartyEvent(String type, UUID partyId, UUID memberUUID,
                                     String memberName, String memberServer) {
        CrossServerParty party = parties.get(partyId);
        if (party == null) return;
        Set<String> servers = new HashSet<>(party.memberServers.values());
        for (String serverId : servers) {
            sendToServer(serverId, type, out -> {
                out.writeUTF(partyId.toString());
                out.writeUTF(memberUUID.toString());
                out.writeUTF(memberName);
                out.writeUTF(memberServer);
            });
        }
    }

    /** Sends the full replay index to a specific server. */
    private void sendFullIndexToServer(String serverId) {
        List<ReplayIndexEntry> valid = replayIndex.values().stream()
                .filter(e -> !e.isExpired())
                .collect(Collectors.toList());
        if (valid.isEmpty()) return;
        sendToServer(serverId, "REPLAY_INDEX_FULL", out -> {
            out.writeInt(valid.size());
            for (ReplayIndexEntry e : valid) {
                out.writeUTF(e.gameId.toString());
                out.writeUTF(e.gameType);
                out.writeLong(e.recordedAt);
                out.writeLong(e.durationSeconds);
                out.writeInt(e.participantCount);
                out.writeUTF(e.hostServerId);
            }
        });
    }

    /** Broadcasts a single index entry to all servers except the one that sent it. */
    private void broadcastIndexEntry(ReplayIndexEntry entry, String exceptServer) {
        for (String serverId : servers.keySet()) {
            if (serverId.equals(exceptServer)) continue;
            sendToServer(serverId, "REPLAY_INDEX_ENTRY", out -> {
                out.writeUTF(entry.gameId.toString());
                out.writeUTF(entry.gameType);
                out.writeLong(entry.recordedAt);
                out.writeLong(entry.durationSeconds);
                out.writeInt(entry.participantCount);
                out.writeUTF(entry.hostServerId);
            });
        }
    }

    private void connectPlayer(UUID playerUUID, String destServer) {
        ProxiedPlayer p = getProxy().getPlayer(playerUUID);
        if (p != null) p.connect(getProxy().getServerInfo(destServer));
    }

    @FunctionalInterface
    interface DataWriter { void write(DataOutputStream out) throws Exception; }

    private void sendToServer(String serverId, String subchannel, DataWriter writer) {
        ServerInfo server = getProxy().getServerInfo(serverId);
        if (server == null) return;
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(byteOut);
            out.writeUTF(subchannel);
            writer.write(out);
            server.sendData(CHANNEL, byteOut.toByteArray());
        } catch (Exception e) {
            getLogger().warning("Failed to send to " + serverId + ": " + e.getMessage());
        }
    }

    private void broadcastToAll(String subchannel, DataWriter writer) {
        for (String serverId : servers.keySet()) sendToServer(serverId, subchannel, writer);
    }

    public static BungeePlugin getInstance() { return instance; }

    private static class BungeeServerInfo {
        final String serverId;
        int playerCount, queueCount;
        long lastHeartbeat;
        BungeeServerInfo(String id) { this.serverId = id; }
    }
}
