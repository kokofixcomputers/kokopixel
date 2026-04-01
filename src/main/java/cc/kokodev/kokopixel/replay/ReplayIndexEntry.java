package cc.kokodev.kokopixel.replay;

import java.io.Serializable;
import java.util.UUID;

/**
 * Lightweight metadata about a recording — broadcast over plugin messaging
 * so every server knows what replays exist network-wide without transferring
 * the actual recording data.
 */
public class ReplayIndexEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    public final UUID gameId;
    public final String gameType;
    public final long recordedAt;
    public final long durationSeconds;
    public final int participantCount;
    /** Which server holds the .replay file. */
    public final String hostServerId;

    public ReplayIndexEntry(UUID gameId, String gameType, long recordedAt,
                            long durationSeconds, int participantCount, String hostServerId) {
        this.gameId = gameId;
        this.gameType = gameType;
        this.recordedAt = recordedAt;
        this.durationSeconds = durationSeconds;
        this.participantCount = participantCount;
        this.hostServerId = hostServerId;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - recordedAt > 6L * 60 * 60 * 1000;
    }

    public boolean isLocal(String myServerId) {
        return hostServerId.equals(myServerId);
    }
}
