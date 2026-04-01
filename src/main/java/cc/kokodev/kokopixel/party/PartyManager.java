package cc.kokodev.kokopixel.party;

import cc.kokodev.kokopixel.KokoPixel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartyManager {
    private final KokoPixel plugin;
    private final Map<UUID, Party> playerParties = new ConcurrentHashMap<>();
    private final Map<UUID, Party> parties = new ConcurrentHashMap<>();
    /** partyId -> cross-server shadow state */
    private final Map<UUID, CrossServerPartyState> remoteStates = new ConcurrentHashMap<>();

    public PartyManager(KokoPixel plugin) { this.plugin = plugin; }

    public Party createParty(Player leader) {
        if (playerParties.containsKey(leader.getUniqueId())) return null;
        Party party = new Party(plugin, leader);
        parties.put(party.getPartyId(), party);
        playerParties.put(leader.getUniqueId(), party);
        // Notify proxy
        if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null)
            plugin.getBungeeListener().sendPartyCreate(party.getPartyId(), leader.getUniqueId(), leader.getName());
        return party;
    }

    public Optional<Party> getParty(Player p) { return Optional.ofNullable(playerParties.get(p.getUniqueId())); }
    public Optional<Party> getParty(UUID id) { return Optional.ofNullable(parties.get(id)); }
    public boolean isInParty(Player p) { return playerParties.containsKey(p.getUniqueId()); }

    public void addPlayerToParty(Player p, Party party) {
        playerParties.put(p.getUniqueId(), party);
        if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null)
            plugin.getBungeeListener().sendPartyJoin(party.getPartyId(), p.getUniqueId(), p.getName());
    }

    public void removeFromParty(Player p) {
        Party party = playerParties.remove(p.getUniqueId());
        if (party != null && plugin.isBungeeEnabled() && plugin.getBungeeListener() != null)
            plugin.getBungeeListener().sendPartyLeave(party.getPartyId(), p.getUniqueId());
    }

    public void removeParty(UUID id) {
        Party party = parties.remove(id);
        if (party != null) party.getMembers().forEach(m -> playerParties.remove(m.getUniqueId()));
        remoteStates.remove(id);
    }

    public void removeParty(Player p) { playerParties.remove(p.getUniqueId()); }

    /**
     * Returns the total party size including remote members on other servers.
     * Used by QueueManager to check if a party fits in a game.
     */
    public int getCrossServerPartySize(Party party) {
        CrossServerPartyState remote = remoteStates.get(party.getPartyId());
        return party.getSize() + (remote != null ? remote.remoteSize() : 0);
    }

    /**
     * Warp all remote party members to this server for a queue.
     * Local members are handled by the normal queue flow.
     */
    public void warpPartyForQueue(Party party, String gameName) {
        if (!plugin.isBungeeEnabled() || plugin.getBungeeListener() == null) return;
        CrossServerPartyState remote = remoteStates.get(party.getPartyId());
        if (remote == null || remote.remoteSize() == 0) return;
        plugin.getBungeeListener().sendPartyWarpQueue(
                party.getPartyId(), plugin.getServerId(), gameName);
        party.broadcast(Component.text("Warping remote party members to this server for the queue...",
                NamedTextColor.YELLOW));
    }

    /**
     * Warp all remote party members to destServer for a replay.
     */
    public void warpPartyForReplay(Party party, String destServer, UUID gameId) {
        if (!plugin.isBungeeEnabled() || plugin.getBungeeListener() == null) return;
        CrossServerPartyState remote = remoteStates.get(party.getPartyId());
        if (remote == null || remote.remoteSize() == 0) return;
        plugin.getBungeeListener().sendPartyWarpReplay(party.getPartyId(), destServer, gameId);
        party.broadcast(Component.text("Warping remote party members to watch the replay...",
                NamedTextColor.LIGHT_PURPLE));
    }

    // -------------------------------------------------------------------------
    // Remote event handlers — called from BungeeListener on main thread
    // -------------------------------------------------------------------------

    public void onRemoteMemberJoined(UUID partyId, UUID memberUUID, String memberName, String memberServer) {
        CrossServerPartyState state = remoteStates.computeIfAbsent(partyId,
                id -> new CrossServerPartyState(id, memberUUID));
        state.remoteMembers.put(memberUUID, memberServer);
        state.memberNames.put(memberUUID, memberName);
        // Notify local party members
        Party local = parties.get(partyId);
        if (local != null)
            local.broadcast(Component.text(memberName + " joined the party from " + memberServer + "!",
                    NamedTextColor.GREEN));
    }

    public void onRemoteMemberLeft(UUID partyId, UUID memberUUID) {
        CrossServerPartyState state = remoteStates.get(partyId);
        if (state == null) return;
        String name = state.memberNames.getOrDefault(memberUUID, "Unknown");
        state.remoteMembers.remove(memberUUID);
        state.memberNames.remove(memberUUID);
        Party local = parties.get(partyId);
        if (local != null)
            local.broadcast(Component.text(name + " left the party.", NamedTextColor.RED));
    }

    public void onRemotePartyDisbanded(UUID partyId) {
        remoteStates.remove(partyId);
        Party local = parties.get(partyId);
        if (local != null) {
            local.broadcast(Component.text("The party was disbanded.", NamedTextColor.RED));
            removeParty(partyId);
        }
    }

    public void disbandAll() { for (Party p : parties.values()) p.disband(); parties.clear(); playerParties.clear(); remoteStates.clear(); }
}