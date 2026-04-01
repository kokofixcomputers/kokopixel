package cc.kokodev.kokopixel.friends;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.util.Msg;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages friend lists and friend requests.
 * Data is persisted per-player in plugins/KokoPixel/friends/<uuid>.yml
 *
 * Cross-server online status is tracked via the network player list
 * maintained by BungeeListener (remoteOnlinePlayers map).
 */
public class FriendManager {

    private final KokoPixel plugin;
    private final File friendsDir;

    /** playerUUID -> set of friend UUIDs */
    private final Map<UUID, Set<UUID>> friends = new ConcurrentHashMap<>();
    /** playerUUID -> set of UUIDs who sent them a request */
    private final Map<UUID, Set<UUID>> pendingRequests = new ConcurrentHashMap<>();
    /** playerUUID -> display name cache */
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();
    /** Network-wide online players: UUID -> serverId. Updated by BungeeListener. */
    private final Map<UUID, String> networkOnline = new ConcurrentHashMap<>();

    public FriendManager(KokoPixel plugin) {
        this.plugin = plugin;
        this.friendsDir = new File(plugin.getDataFolder(), "friends");
        friendsDir.mkdirs();
    }

    // -------------------------------------------------------------------------
    // Friend requests
    // -------------------------------------------------------------------------

    public void sendRequest(Player sender, Player target) {
        if (sender.equals(target)) { Msg.sendError(sender, "You can't friend yourself."); return; }
        UUID sid = sender.getUniqueId(), tid = target.getUniqueId();
        if (areFriends(sid, tid)) { Msg.sendError(sender, target.getName() + " is already your friend."); return; }
        if (hasPendingRequest(tid, sid)) { Msg.sendError(sender, "You already sent a request to " + target.getName() + "."); return; }
        // If target already sent us a request, auto-accept
        if (hasPendingRequest(sid, tid)) { acceptRequest(sender, target); return; }
        pendingRequests.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet()).add(sid);
        nameCache.put(sid, sender.getName());
        Msg.sendSuccess(sender, "Friend request sent to " + target.getName() + ".");
        target.sendMessage(Msg.friendRequest(sender.getName()));
    }

    public void acceptRequest(Player accepter, Player requester) {
        UUID aid = accepter.getUniqueId(), rid = requester.getUniqueId();
        Set<UUID> pending = pendingRequests.get(aid);
        if (pending == null || !pending.contains(rid)) {
            Msg.sendError(accepter, "No pending friend request from " + requester.getName() + ".");
            return;
        }
        pending.remove(rid);
        addFriend(aid, rid, accepter.getName(), requester.getName());
        Msg.sendSuccess(accepter, "You are now friends with " + requester.getName() + "!");
        Msg.sendSuccess(requester, accepter.getName() + " accepted your friend request!");
    }

    public void denyRequest(Player denier, String requesterName) {
        UUID did = denier.getUniqueId();
        // Find by name in pending
        Set<UUID> pending = pendingRequests.get(did);
        if (pending == null) { Msg.sendError(denier, "No pending request from " + requesterName + "."); return; }
        UUID rid = pending.stream()
                .filter(u -> requesterName.equalsIgnoreCase(nameCache.getOrDefault(u, "")))
                .findFirst().orElse(null);
        if (rid == null) { Msg.sendError(denier, "No pending request from " + requesterName + "."); return; }
        pending.remove(rid);
        Msg.sendInfo(denier, "Denied friend request from " + requesterName + ".");
    }

    public void removeFriend(Player player, String targetName) {
        UUID pid = player.getUniqueId();
        UUID tid = friends.getOrDefault(pid, Collections.emptySet()).stream()
                .filter(u -> targetName.equalsIgnoreCase(nameCache.getOrDefault(u, "")))
                .findFirst().orElse(null);
        if (tid == null) { Msg.sendError(player, targetName + " is not in your friend list."); return; }
        friends.getOrDefault(pid, Collections.emptySet()).remove(tid);
        friends.getOrDefault(tid, Collections.emptySet()).remove(pid);
        saveFriends(pid); saveFriends(tid);
        Msg.sendInfo(player, "Removed " + targetName + " from your friends.");
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public boolean areFriends(UUID a, UUID b) {
        return friends.getOrDefault(a, Collections.emptySet()).contains(b);
    }

    public boolean hasPendingRequest(UUID target, UUID from) {
        return pendingRequests.getOrDefault(target, Collections.emptySet()).contains(from);
    }

    public Set<UUID> getFriends(UUID playerId) {
        return Collections.unmodifiableSet(friends.getOrDefault(playerId, Collections.emptySet()));
    }

    /** Returns the serverId the friend is on, or null if offline. */
    public String getFriendServer(UUID friendId) {
        // Check local first
        Player local = plugin.getServer().getPlayer(friendId);
        if (local != null && local.isOnline()) return plugin.getServerId();
        // Then network map
        return networkOnline.get(friendId);
    }

    public boolean isFriendOnline(UUID friendId) { return getFriendServer(friendId) != null; }

    public String getName(UUID uuid) {
        Player local = plugin.getServer().getPlayer(uuid);
        if (local != null) return local.getName();
        return nameCache.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    // -------------------------------------------------------------------------
    // Network online status — updated by BungeeListener
    // -------------------------------------------------------------------------

    public void setNetworkOnline(UUID playerId, String serverId, String name) {
        networkOnline.put(playerId, serverId);
        nameCache.put(playerId, name);
        // Notify local friends
        notifyFriendsOnline(playerId, serverId, name);
    }

    public void setNetworkOffline(UUID playerId) {
        networkOnline.remove(playerId);
        String name = nameCache.getOrDefault(playerId, "Unknown");
        notifyFriendsOffline(playerId, name);
    }

    private void notifyFriendsOnline(UUID playerId, String serverId, String name) {
        for (UUID friendId : getFriends(playerId)) {
            Player local = plugin.getServer().getPlayer(friendId);
            if (local != null && local.isOnline())
                local.sendMessage(Msg.friendOnline(name, serverId));
        }
    }

    private void notifyFriendsOffline(UUID playerId, String name) {
        for (UUID friendId : getFriends(playerId)) {
            Player local = plugin.getServer().getPlayer(friendId);
            if (local != null && local.isOnline())
                local.sendMessage(Msg.friendOffline(name));
        }
    }

    // -------------------------------------------------------------------------
    // Load / save
    // -------------------------------------------------------------------------

    public void loadFriends(UUID playerId, String playerName) {
        nameCache.put(playerId, playerName);
        File file = fileFor(playerId);
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Set<UUID> list = ConcurrentHashMap.newKeySet();
        for (String s : cfg.getStringList("friends")) {
            try { list.add(UUID.fromString(s)); } catch (Exception ignored) {}
        }
        friends.put(playerId, list);
        // Load name cache entries
        var names = cfg.getConfigurationSection("names");
        if (names != null) for (String key : names.getKeys(false)) {
            try { nameCache.put(UUID.fromString(key), names.getString(key)); } catch (Exception ignored) {}
        }
    }

    public void saveFriends(UUID playerId) {
        File file = fileFor(playerId);
        YamlConfiguration cfg = new YamlConfiguration();
        Set<UUID> list = friends.getOrDefault(playerId, Collections.emptySet());
        cfg.set("friends", list.stream().map(UUID::toString).toList());
        // Save names of all friends so we can display them offline
        for (UUID fid : list) cfg.set("names." + fid, nameCache.getOrDefault(fid, "Unknown"));
        try { cfg.save(file); } catch (IOException e) { plugin.getLogger().warning("Failed to save friends for " + playerId); }
    }

    public void unload(UUID playerId) {
        saveFriends(playerId);
        friends.remove(playerId);
        // Keep nameCache — it's small and useful for offline display
    }

    private void addFriend(UUID a, UUID b, String nameA, String nameB) {
        friends.computeIfAbsent(a, k -> ConcurrentHashMap.newKeySet()).add(b);
        friends.computeIfAbsent(b, k -> ConcurrentHashMap.newKeySet()).add(a);
        nameCache.put(a, nameA); nameCache.put(b, nameB);
        saveFriends(a); saveFriends(b);
    }

    private File fileFor(UUID uuid) { return new File(friendsDir, uuid + ".yml"); }
}
