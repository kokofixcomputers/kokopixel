package cc.kokodev.kokopixel.velocity;

import cc.kokodev.kokopixel.replay.ReplayIndexEntry;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import org.slf4j.Logger;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Velocity-side KokoPixel proxy plugin.
 *
 * Mirrors BungeePlugin exactly — same protocol, same subchannel names,
 * same data layout. The Paper-side BungeeListener is completely unaware
 * of which proxy it is talking to.
 */
@Plugin(
    id = "kokopixel",
    name = "KokoPixel",
    version = "2.0.0",
    description = "KokoPixel proxy component",
    authors = {"KokoDev"}
)
public class VelocityPlugin {

    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("kokopixel:main");

    private final ProxyServer proxy;
    private final Logger logger;

    // serverId -> stats
    private final Map<String, ServerStats> servers = new ConcurrentHashMap<>();
    // gameId -> replay index entry
    private final Map<UUID, ReplayIndexEntry> replayIndex = new ConcurrentHashMap<>();
    // partyId -> party
    private final Map<UUID, VelocityParty> parties = new ConcurrentHashMap<>();
    // memberUUID -> partyId
    private final Map<UUID, UUID> playerParty = new ConcurrentHashMap<>();

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getEventManager().register(this, this);
        logger.info("KokoPixel Velocity proxy enabled!");
    }

    // =========================================================================
    // Player connect / disconnect
    // =========================================================================

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getUsername();
        String serverId = event.getServer().getServerInfo().getName();

        // Update party member location
        UUID partyId = playerParty.get(uuid);
        if (partyId != null) {
            VelocityParty party = parties.get(partyId);
            if (party != null) party.memberServers.put(uuid, serverId);
        }

        // Broadcast player online to all backend servers
        broadcastToAll("PLAYER_ONLINE", out -> {
            out.writeUTF(uuid.toString());
            out.writeUTF(name);
            out.writeUTF(serverId);
        });

        // Send full replay index to the newly connected server
        sendFullIndexToServer(serverId);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        broadcastToAll("PLAYER_OFFLINE", out -> out.writeUTF(uuid.toString()));

        UUID partyId = playerParty.get(uuid);
        if (partyId != null) {
            VelocityParty party = parties.get(partyId);
            if (party != null) {
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

    @Subscribe
    public void onPluginMessage(com.velocitypowered.api.event.connection.PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        // Mark as handled so it doesn't get forwarded to the client
        event.setResult(com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult.handled());
        // Only handle messages from backend servers
        if (!(event.getSource() instanceof ServerConnection backend)) return;

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
            String sub = in.readUTF();

            switch (sub) {

                case "HEARTBEAT": {
                    String serverId = in.readUTF();
                    int players = in.readInt();
                    int queued  = in.readInt();
                    ServerStats stats = servers.computeIfAbsent(serverId, ServerStats::new);
                    stats.playerCount   = players;
                    stats.queueCount    = queued;
                    stats.lastHeartbeat = System.currentTimeMillis();
                    break;
                }

                case "REPLAY_INDEX_UPDATE": {
                    String fromServer  = in.readUTF();
                    UUID   gameId      = UUID.fromString(in.readUTF());
                    String gameType    = in.readUTF();
                    long   recordedAt  = in.readLong();
                    long   durationSec = in.readLong();
                    int    participants = in.readInt();
                    ReplayIndexEntry entry = new ReplayIndexEntry(
                            gameId, gameType, recordedAt, durationSec, participants, fromServer);
                    if (entry.isExpired()) replayIndex.remove(gameId);
                    else replayIndex.put(gameId, entry);
                    broadcastIndexEntry(entry, fromServer);
                    break;
                }

                case "REPLAY_INDEX_REQUEST": {
                    String requestingServer = in.readUTF();
                    sendFullIndexToServer(requestingServer);
                    break;
                }

                case "PARTY_CREATE": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    UUID   leaderUUID = UUID.fromString(in.readUTF());
                    String leaderName = in.readUTF();
                    String fromServer = in.readUTF();
                    VelocityParty party = new VelocityParty(partyId, leaderUUID, leaderName, fromServer);
                    parties.put(partyId, party);
                    playerParty.put(leaderUUID, partyId);
                    break;
                }

                case "PARTY_JOIN": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    UUID   memberUUID = UUID.fromString(in.readUTF());
                    String memberName = in.readUTF();
                    String fromServer = in.readUTF();
                    VelocityParty party = parties.get(partyId);
                    if (party != null) {
                        party.memberServers.put(memberUUID, fromServer);
                        party.memberNames.put(memberUUID, memberName);
                        playerParty.put(memberUUID, partyId);
                        broadcastPartyEvent("PARTY_MEMBER_JOINED", partyId, memberUUID, memberName, fromServer);
                    }
                    break;
                }

                case "PARTY_LEAVE": {
                    UUID partyId    = UUID.fromString(in.readUTF());
                    UUID memberUUID = UUID.fromString(in.readUTF());
                    VelocityParty party = parties.get(partyId);
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

                case "PARTY_WARP_QUEUE": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    String destServer = in.readUTF();
                    String gameName   = in.readUTF();
                    VelocityParty party = parties.get(partyId);
                    if (party == null) break;
                    for (Map.Entry<UUID, String> entry : party.memberServers.entrySet()) {
                        if (entry.getKey().equals(party.leaderUUID)) continue;
                        if (!entry.getValue().equals(destServer)) {
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

                case "PARTY_WARP_REPLAY": {
                    UUID   partyId    = UUID.fromString(in.readUTF());
                    String destServer = in.readUTF();
                    UUID   gameId     = UUID.fromString(in.readUTF());
                    VelocityParty party = parties.get(partyId);
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

                case "REPLAY_START": {
                    String targetServer = in.readUTF();
                    UUID   playerId     = UUID.fromString(in.readUTF());
                    UUID   gameId       = UUID.fromString(in.readUTF());
                    // Forward PENDING_REPLAY to the target server, then connect the player
                    sendToServer(targetServer, "PENDING_REPLAY", out -> {
                        out.writeUTF(playerId.toString());
                        out.writeUTF(targetServer);
                        out.writeUTF(gameId.toString());
                    });
                    connectPlayer(playerId, targetServer);
                    break;
                }

                case "DIRECT_MSG":
                case "QUEUE_REPORT":
                case "MERGE_INTO":
                case "QUEUE_REQUEST": {
                    // Forward raw bytes to all servers except the sender
                    forwardToAllExcept(backend.getServerInfo().getName(), event.getData());
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("Error processing plugin message: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void disbandParty(UUID partyId) {
        VelocityParty party = parties.remove(partyId);
        if (party == null) return;
        Set<String> affectedServers = new HashSet<>(party.memberServers.values());
        for (String serverId : affectedServers)
            sendToServer(serverId, "PARTY_DISBANDED", out -> out.writeUTF(partyId.toString()));
        party.memberServers.keySet().forEach(playerParty::remove);
    }

    private void broadcastPartyEvent(String type, UUID partyId, UUID memberUUID,
                                     String memberName, String memberServer) {
        VelocityParty party = parties.get(partyId);
        if (party == null) return;
        Set<String> serverIds = new HashSet<>(party.memberServers.values());
        for (String serverId : serverIds) {
            sendToServer(serverId, type, out -> {
                out.writeUTF(partyId.toString());
                out.writeUTF(memberUUID.toString());
                out.writeUTF(memberName);
                out.writeUTF(memberServer);
            });
        }
    }

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
        proxy.getPlayer(playerUUID).ifPresent(p ->
            proxy.getServer(destServer).ifPresent(s ->
                p.createConnectionRequest(s).fireAndForget()));
    }

    @FunctionalInterface
    interface DataWriter { void write(DataOutputStream out) throws Exception; }

    private void sendToServer(String serverId, String subchannel, DataWriter writer) {
        proxy.getServer(serverId).ifPresent(server -> {
            try {
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(byteOut);
                out.writeUTF(subchannel);
                writer.write(out);
                server.sendPluginMessage(CHANNEL, byteOut.toByteArray());
            } catch (Exception e) {
                logger.warn("Failed to send to {}: {}", serverId, e.getMessage());
            }
        });
    }

    private void broadcastToAll(String subchannel, DataWriter writer) {
        for (RegisteredServer server : proxy.getAllServers()) {
            try {
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(byteOut);
                out.writeUTF(subchannel);
                writer.write(out);
                server.sendPluginMessage(CHANNEL, byteOut.toByteArray());
            } catch (Exception e) {
                logger.warn("broadcastToAll failed for {}: {}", server.getServerInfo().getName(), e.getMessage());
            }
        }
    }

    /** Forward raw plugin message bytes to all servers except the sender. */
    private void forwardToAllExcept(String exceptServer, byte[] data) {
        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equals(exceptServer)) continue;
            server.sendPluginMessage(CHANNEL, data);
        }
    }

    private static class ServerStats {
        final String serverId;
        int playerCount, queueCount;
        long lastHeartbeat;
        ServerStats(String id) { this.serverId = id; }
    }
}
