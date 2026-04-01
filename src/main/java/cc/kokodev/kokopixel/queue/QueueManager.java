package cc.kokodev.kokopixel.queue;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.Minigame;
import cc.kokodev.kokopixel.party.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class QueueManager {
    private final KokoPixel plugin;
    private final Map<String, List<GameQueue>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, GameQueue> playerQueues = new ConcurrentHashMap<>();
    private final Map<UUID, GameQueue> partyQueues = new ConcurrentHashMap<>();

    public QueueManager(KokoPixel plugin) { this.plugin = plugin; }

    public boolean addToQueue(Player player, Minigame minigame) {
        if (playerQueues.containsKey(player.getUniqueId())) { player.sendMessage("§cYou're already in a queue!"); return false; }
        if (plugin.getMinigameManager().isInGame(player)) { player.sendMessage("§cYou're already in a game!"); return false; }
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isPresent()) {
            if (!party.get().isLeader(player)) { player.sendMessage("§cOnly the party leader can queue!"); return false; }
            return addPartyToQueue(party.get(), minigame);
        }
        return addIndividualToQueue(player, minigame);
    }

    private boolean addIndividualToQueue(Player p, Minigame mg) {
        List<GameQueue> list = queues.computeIfAbsent(mg.getName(), k -> new ArrayList<>());
        GameQueue q = list.stream().filter(qq -> !qq.isFull() && !qq.isPrivate()).findFirst().orElseGet(() -> { GameQueue nq = new GameQueue(plugin, mg, QueueType.PUBLIC); list.add(nq); return nq; });
        if (q.addPlayer(p)) { playerQueues.put(p.getUniqueId(), q); return true; }
        return false;
    }

    private boolean addPartyToQueue(Party party, Minigame mg) {
        if (partyQueues.containsKey(party.getPartyId())) { party.getLeader().sendMessage("§cYour party is already in a queue!"); return false; }
        List<Player> members = party.getOnlineMembers();
        // Include remote members in size check
        int totalSize = plugin.getPartyManager().getCrossServerPartySize(party);
        if (party.isPrivate()) {
            List<GameQueue> list = queues.computeIfAbsent(mg.getName(), k -> new ArrayList<>());
            GameQueue q = new GameQueue(plugin, mg, QueueType.PRIVATE, party);
            list.add(q);
            if (q.addParty(party)) {
                partyQueues.put(party.getPartyId(), q);
                for (Player m : members) playerQueues.put(m.getUniqueId(), q);
                // Warp remote members to this server
                plugin.getPartyManager().warpPartyForQueue(party, mg.getName());
                return true;
            }
            return false;
        }
        List<GameQueue> list = queues.computeIfAbsent(mg.getName(), k -> new ArrayList<>());
        GameQueue q = list.stream().filter(qq -> !qq.isPrivate() && qq.canFit(totalSize)).findFirst().orElse(null);
        if (q == null && totalSize <= mg.getMaxPlayers()) { q = new GameQueue(plugin, mg, QueueType.PUBLIC); list.add(q); }
        if (q != null && q.addParty(party)) {
            partyQueues.put(party.getPartyId(), q);
            for (Player m : members) playerQueues.put(m.getUniqueId(), q);
            // Warp remote members to this server
            plugin.getPartyManager().warpPartyForQueue(party, mg.getName());
            return true;
        }
        party.getLeader().sendMessage("§cCannot fit party of " + totalSize + " into queue (max: " + mg.getMaxPlayers() + ")");
        return false;
    }

    public boolean removeFromQueue(Player p) {
        GameQueue q = playerQueues.remove(p.getUniqueId());
        if (q != null) { q.removePlayer(p); cleanupEmptyQueues(); return true; }
        return false;
    }

    public void removePartyFromQueue(Party party) {
        GameQueue q = partyQueues.remove(party.getPartyId());
        if (q != null) { for (Player m : party.getOnlineMembers()) { playerQueues.remove(m.getUniqueId()); q.removePlayer(m); } }
    }

    public boolean isInQueue(Player p) { return playerQueues.containsKey(p.getUniqueId()); }
    public int getQueueSize(String game) { return queues.getOrDefault(game, new ArrayList<>()).stream().mapToInt(GameQueue::getPlayerCount).sum(); }
    public int getTotalQueueSize() { return queues.values().stream().flatMap(List::stream).mapToInt(GameQueue::getPlayerCount).sum(); }

    public void cleanupEmptyQueues() {
        for (List<GameQueue> list : queues.values()) list.removeIf(GameQueue::isEmpty);
        queues.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Cross-server queue merging
    // -------------------------------------------------------------------------

    /**
     * Called every 60s. Compares our local queue sizes against reported sizes
     * from other servers. If another server has a bigger queue for the same game,
     * we send a MERGE_INTO request so our players move there.
     * If we are the biggest, we send MERGE_INTO to the smaller servers.
     */
    public void evaluateCrossServerMerge(Map<String, Map<String, Integer>> remoteReports) {
        if (!plugin.isBungeeEnabled() || remoteReports.isEmpty()) return;

        for (String gameName : plugin.getMinigameManager().getMinigameNames()) {
            int localSize = getQueueSize(gameName);
            if (localSize == 0) continue;

            Minigame mg = plugin.getMinigameManager().getMinigame(gameName);
            if (mg == null) continue;

            // Find the server with the largest queue for this game (including ourselves)
            String biggestServer = plugin.getServerId();
            int biggestSize = localSize;

            for (Map.Entry<String, Map<String, Integer>> entry : remoteReports.entrySet()) {
                int remoteSize = entry.getValue().getOrDefault(gameName, 0);
                if (remoteSize > biggestSize) {
                    biggestSize = remoteSize;
                    biggestServer = entry.getKey();
                }
            }

            // If we are NOT the biggest, and the combined total fits in one game, dissolve ours
            if (!biggestServer.equals(plugin.getServerId())) {
                int combined = localSize + biggestSize;
                if (combined <= mg.getMaxPlayers()) {
                    plugin.getLogger().info("[QueueMerge] Merging " + localSize + " players from "
                        + gameName + " queue into server " + biggestServer);
                    dissolveQueueToServer(gameName, biggestServer);
                }
            } else {
                // We are the biggest — tell smaller servers to merge into us
                for (Map.Entry<String, Map<String, Integer>> entry : remoteReports.entrySet()) {
                    int remoteSize = entry.getValue().getOrDefault(gameName, 0);
                    if (remoteSize == 0) continue;
                    int combined = localSize + remoteSize;
                    if (combined <= mg.getMaxPlayers()) {
                        plugin.getLogger().info("[QueueMerge] Requesting server " + entry.getKey()
                            + " to merge " + remoteSize + " " + gameName + " players into us");
                        plugin.getBungeeListener().sendMergeRequest(entry.getKey(), gameName, remoteSize);
                    }
                }
            }
        }
    }

    /**
     * Dissolves our local queue for a game and sends all queued players
     * to the destination server via BungeeCord. They will re-queue automatically
     * on arrival via the QUEUE_REQUEST message sent ahead of them.
     */
    public void dissolveQueueToServer(String gameName, String destServerId) {
        List<GameQueue> list = queues.getOrDefault(gameName, Collections.emptyList());
        List<Player> toMove = new ArrayList<>();

        for (GameQueue q : list) {
            if (q.isPrivate()) continue; // never merge private queues
            toMove.addAll(q.getOnlinePlayers());
        }

        if (toMove.isEmpty()) return;

        for (Player p : toMove) {
            p.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                .append(Component.text("Merging you into a larger queue on another server...", NamedTextColor.YELLOW)));

            // Tell the destination server to queue this player when they arrive
            plugin.getBungeeListener().sendToAll("QUEUE_REQUEST", out -> {
                out.writeUTF(gameName);
                out.writeUTF(p.getUniqueId().toString());
            });

            // Remove from local queue first, then connect
            removeFromQueue(p);
            connectToServer(p, destServerId);
        }

        cleanupEmptyQueues();
    }

    /** Sends a player to another server via BungeeCord plugin messaging. */
    private void connectToServer(Player p, String serverId) {
        try {
            java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(byteOut);
            out.writeUTF("Connect");
            out.writeUTF(serverId);
            p.sendPluginMessage(plugin, "BungeeCord", byteOut.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to connect " + p.getName() + " to " + serverId + ": " + e.getMessage());
        }
    }
}