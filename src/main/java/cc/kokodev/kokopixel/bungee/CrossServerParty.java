package cc.kokodev.kokopixel.bungee;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight cross-server party state stored in BungeePlugin.
 * Tracks which server each member is on so the proxy can route them.
 */
public class CrossServerParty {
    public final UUID partyId;
    public final UUID leaderUUID;
    public final String leaderName;
    /** memberUUID -> serverId they are currently on */
    public final Map<UUID, String> memberServers = new ConcurrentHashMap<>();
    /** memberUUID -> display name */
    public final Map<UUID, String> memberNames = new ConcurrentHashMap<>();

    public CrossServerParty(UUID partyId, UUID leaderUUID, String leaderName, String leaderServer) {
        this.partyId = partyId;
        this.leaderUUID = leaderUUID;
        this.leaderName = leaderName;
        memberServers.put(leaderUUID, leaderServer);
        memberNames.put(leaderUUID, leaderName);
    }

    public boolean isLeader(UUID uuid) { return leaderUUID.equals(uuid); }
    public Set<UUID> getMembers() { return memberServers.keySet(); }
}
