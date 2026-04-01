package cc.kokodev.kokopixel.party;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-side shadow of a cross-server party.
 * Tracks remote members (those not on this server) so the local PartyManager
 * can include them in size checks and warp operations.
 */
public class CrossServerPartyState {
    public final UUID partyId;
    public final UUID leaderUUID;
    /** memberUUID -> serverId (only remote members tracked here) */
    public final Map<UUID, String> remoteMembers = new ConcurrentHashMap<>();
    /** memberUUID -> display name */
    public final Map<UUID, String> memberNames = new ConcurrentHashMap<>();

    public CrossServerPartyState(UUID partyId, UUID leaderUUID) {
        this.partyId = partyId;
        this.leaderUUID = leaderUUID;
    }

    public int remoteSize() { return remoteMembers.size(); }
}
