package cc.kokodev.kokopixel.velocity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity-side cross-server party state. Mirrors BungeeCord's CrossServerParty. */
public class VelocityParty {
    public final UUID partyId;
    public final UUID leaderUUID;
    public final String leaderName;
    public final Map<UUID, String> memberServers = new ConcurrentHashMap<>();
    public final Map<UUID, String> memberNames   = new ConcurrentHashMap<>();

    public VelocityParty(UUID partyId, UUID leaderUUID, String leaderName, String leaderServer) {
        this.partyId    = partyId;
        this.leaderUUID = leaderUUID;
        this.leaderName = leaderName;
        memberServers.put(leaderUUID, leaderServer);
        memberNames.put(leaderUUID, leaderName);
    }

    public boolean isLeader(UUID uuid) { return leaderUUID.equals(uuid); }
    public Set<UUID> getMembers() { return memberServers.keySet(); }
}
