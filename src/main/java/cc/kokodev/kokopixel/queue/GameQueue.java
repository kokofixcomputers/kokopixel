package cc.kokodev.kokopixel.queue;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.Minigame;
import cc.kokodev.kokopixel.party.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameQueue {
    private final KokoPixel plugin;
    private final Minigame minigame;
    private final QueueType type;
    private final List<UUID> players = new CopyOnWriteArrayList<>();
    private final Map<UUID, Party> playerParties = new HashMap<>();
    private Party privateParty;
    private boolean countdownActive = false;
    private int countdownTaskId = -1;

    public GameQueue(KokoPixel plugin, Minigame mg, QueueType type) { this.plugin = plugin; this.minigame = mg; this.type = type; }
    public GameQueue(KokoPixel plugin, Minigame mg, QueueType type, Party party) { this(plugin, mg, type); this.privateParty = party; }

    public boolean addPlayer(Player p) {
        if (players.contains(p.getUniqueId())) return false;
        players.add(p.getUniqueId());
        p.sendMessage(Component.text("✦ ", NamedTextColor.GOLD).append(Component.text("You joined the queue for ", NamedTextColor.GRAY)).append(Component.text(minigame.getDisplayName(), NamedTextColor.YELLOW)));
        broadcastQueueStatus();
        checkStart();
        return true;
    }

    public boolean addParty(Party party) {
        List<Player> members = party.getOnlineMembers();
        if (!canFit(members.size())) return false;
        if (type == QueueType.PRIVATE && !party.equals(privateParty)) return false;
        for (Player m : members) { players.add(m.getUniqueId()); playerParties.put(m.getUniqueId(), party); m.sendMessage(Component.text("✦ ", NamedTextColor.GOLD).append(Component.text("Your party joined the queue for ", NamedTextColor.GRAY)).append(Component.text(minigame.getDisplayName(), NamedTextColor.YELLOW))); }
        broadcastQueueStatus();
        checkStart();
        return true;
    }

    public void removePlayer(Player p) {
        UUID id = p.getUniqueId();
        if (players.remove(id)) {
            Party party = playerParties.remove(id);
            if (party != null && !party.getLeader().equals(p)) {
                boolean anyLeft = players.stream().anyMatch(uid -> party.equals(playerParties.get(uid)));
                if (!anyLeft) for (Player m : party.getOnlineMembers()) if (!m.equals(p)) { players.remove(m.getUniqueId()); playerParties.remove(m.getUniqueId()); m.sendMessage(Component.text("Your party left the queue!", NamedTextColor.RED)); }
            }
            broadcastQueueStatus();
            if (players.size() < minigame.getMinPlayers() && countdownActive) cancelCountdown();
        }
    }

    private void checkStart() {
        if (countdownActive) return;
        if (type == QueueType.PRIVATE) { if (!players.isEmpty()) startGame(); }
        else if (players.size() >= minigame.getMinPlayers()) startCountdown();
    }

    private void startCountdown() {
        countdownActive = true;
        countdownTaskId = new BukkitRunnable() {
            int cd = 5;
            @Override public void run() {
                if (players.size() < minigame.getMinPlayers()) { broadcast("§cNot enough players! Countdown cancelled."); countdownActive = false; cancel(); return; }
                if (cd == 0) { startGame(); cancel(); }
                else { broadcast("§eGame starting in " + cd + " seconds... (" + players.size() + "/" + minigame.getMaxPlayers() + " players)"); cd--; }
            }
        }.runTaskTimer(plugin, 0L, 20L).getTaskId();
    }

    private void cancelCountdown() { if (countdownTaskId != -1) { plugin.getServer().getScheduler().cancelTask(countdownTaskId); broadcast("§cCountdown cancelled."); countdownActive = false; } }

    private void startGame() {
        List<Player> list = players.stream()
                .map(uid -> plugin.getServer().getPlayer(uid))
                .filter(p -> p != null && p.isOnline())
                .toList();
        if (list.isEmpty()) return;
        broadcast("§a§lGame starting now!");
        // Clear queue state immediately so removePlayer callbacks during world load
        // don't see a shrinking player list and cancel the (already-fired) countdown.
        List<UUID> snapshot = new ArrayList<>(players);
        Map<UUID, Party> partiesSnapshot = new HashMap<>(playerParties);
        players.clear();
        playerParties.clear();
        countdownActive = false;
        // MinigameManager.startGame handles removeFromQueue for each player inside
        // its async callback — we must NOT call it here again or it double-removes.
        plugin.getMinigameManager().startGame(minigame, list, type == QueueType.PRIVATE);
        // Clean up party queue entries
        for (Party party : partiesSnapshot.values()) {
            if (party != null) plugin.getQueueManager().removePartyFromQueue(party);
        }
    }

    private void broadcast(String msg) { Component c = Component.text("[" + minigame.getDisplayName() + "] ", NamedTextColor.GOLD).append(Component.text(msg, NamedTextColor.WHITE)); for (UUID id : players) { Player p = plugin.getServer().getPlayer(id); if (p != null) p.sendMessage(c); } }
    private void broadcastQueueStatus() { if (type != QueueType.PRIVATE) broadcast("§7Queue: §e" + players.size() + "§7/§e" + minigame.getMaxPlayers() + " §7(Need §e" + (minigame.getMinPlayers() - players.size()) + "§7 more to start)"); }
    public boolean canFit(int size) { return players.size() + size <= minigame.getMaxPlayers(); }
    public boolean isFull() { return players.size() >= minigame.getMaxPlayers(); }
    public boolean isEmpty() { return players.isEmpty(); }
    public boolean isPrivate() { return type == QueueType.PRIVATE; }
    public int getPlayerCount() { return players.size(); }
    public List<Player> getOnlinePlayers() {
        return players.stream()
            .map(uid -> plugin.getServer().getPlayer(uid))
            .filter(p -> p != null && p.isOnline())
            .collect(java.util.stream.Collectors.toList());
    }
}