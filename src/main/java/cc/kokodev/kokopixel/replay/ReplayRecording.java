package cc.kokodev.kokopixel.replay;

import java.io.*;
import java.util.*;

/**
 * Complete recording of one game instance.
 * Serialized to disk as a .replay binary file.
 */
public class ReplayRecording implements Serializable {
    private static final long serialVersionUID = 1L;

    public final UUID gameId;
    public final String gameType;
    public final String minigameName;       // used to find the template world on playback
    public final long recordedAt;           // epoch ms — used for 6-hour expiry
    public final long durationTicks;
    /** UUIDs of players who participated — used to filter /replay list. */
    public final Set<UUID> participants;
    /** Ordered list of frames, one per recorded tick. */
    public final List<ReplayFrame> frames;

    public ReplayRecording(UUID gameId, String gameType, String minigameName,
                           long recordedAt, Set<UUID> participants, List<ReplayFrame> frames) {
        this.gameId = gameId;
        this.gameType = gameType;
        this.minigameName = minigameName;
        this.recordedAt = recordedAt;
        this.participants = participants;
        this.frames = frames;
        this.durationTicks = frames.size();
    }

    /** Seconds of real gameplay. */
    public long durationSeconds() { return durationTicks / 20; }

    /** True if this recording is older than 6 hours. */
    public boolean isExpired() {
        return System.currentTimeMillis() - recordedAt > 6L * 60 * 60 * 1000;
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    public void saveTo(File file) throws IOException {
        file.getParentFile().mkdirs();
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            oos.writeObject(this);
        }
    }

    public static ReplayRecording loadFrom(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            return (ReplayRecording) ois.readObject();
        }
    }
}
