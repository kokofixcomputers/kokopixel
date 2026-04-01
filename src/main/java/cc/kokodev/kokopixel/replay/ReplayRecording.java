package cc.kokodev.kokopixel.replay;

import java.io.*;
import java.util.*;

/**
 * Complete recording of one game instance.
 * Serialized to disk as a .replay binary file.
 */
public class ReplayRecording implements Serializable {
    private static final long serialVersionUID = 1L; // must stay 1L for backward compat

    public final UUID gameId;
    public final String gameType;
    public final String minigameName;
    public final long recordedAt;
    public final long durationTicks;
    public final Set<UUID> participants;
    public final List<ReplayFrame> frames;

    /**
     * Skin texture properties per participant, captured at record time.
     * Stored as "value\nsignature" per UUID.
     * Null in recordings made before this field was added — handled in getSkinTextures().
     */
    private final Map<UUID, String> skinTextures;

    /** Always-safe accessor — returns empty map for old recordings that predate this field. */
    public Map<UUID, String> getSkinTextures() {
        return skinTextures != null ? skinTextures : Collections.emptyMap();
    }

    public ReplayRecording(UUID gameId, String gameType, String minigameName,
                           long recordedAt, Set<UUID> participants, List<ReplayFrame> frames,
                           Map<UUID, String> skinTextures) {
        this.gameId = gameId;
        this.gameType = gameType;
        this.minigameName = minigameName;
        this.recordedAt = recordedAt;
        this.participants = participants;
        this.frames = frames;
        this.durationTicks = frames.size();
        this.skinTextures = skinTextures != null ? skinTextures : new java.util.HashMap<>();
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

    /**
     * Custom readObject so that skinTextures (added later) deserializes gracefully
     * on recordings that predate the field — ObjectInputStream sets missing fields
     * to null, which getSkinTextures() handles.
     */
    private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException {
        in.defaultReadObject();
    }

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
        } catch (java.io.InvalidClassException e) {
            // File was written with a different serialVersionUID (e.g. the brief period
            // where it was 2L). These files cannot be recovered — delete them.
            file.delete();
            throw e;
        }
    }
}
