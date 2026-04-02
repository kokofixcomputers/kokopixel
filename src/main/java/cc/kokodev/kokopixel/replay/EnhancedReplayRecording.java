package cc.kokodev.kokopixel.replay;

import java.io.*;
import java.util.*;

/**
 * Enhanced recording of one game instance using the new ReplayData system.
 * Serialized to disk as .replay files.
 */
public class EnhancedReplayRecording implements Serializable {
    private static final long serialVersionUID = 2L; // New version for enhanced system

    public final UUID gameId;
    public final String gameType;
    public final String minigameName;
    public final long recordedAt;
    public final long durationTicks;
    public final Set<UUID> participants;
    public final List<ReplayData> frames;

    /**
     * Skin texture properties per participant, captured at record time.
     * Stored as "value\nsignature" per UUID.
     */
    private final Map<UUID, String> skinTextures;

    /** Always-safe accessor — returns empty map for old recordings that predate this field. */
    public Map<UUID, String> getSkinTextures() {
        return skinTextures != null ? skinTextures : Collections.emptyMap();
    }

    public EnhancedReplayRecording(UUID gameId, String gameType, String minigameName,
                           long recordedAt, Set<UUID> participants, List<ReplayData> frames,
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
        return System.currentTimeMillis() - recordedAt > 6 * 60 * 60 * 1000;
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Save this recording to a file. */
    public void saveTo(File file) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
            out.writeObject(this);
        }
    }

    /** Load a recording from a file. Returns null if the file format is unsupported. */
    public static EnhancedReplayRecording loadFrom(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            Object obj = in.readObject();
            if (obj instanceof EnhancedReplayRecording) {
                return (EnhancedReplayRecording) obj;
            }
            throw new IOException("Unsupported replay format: " + obj.getClass().getName());
        }
    }

    /** Convert old ReplayFrame to ReplayData - simplified version */
    public static EnhancedReplayRecording fromLegacy(ReplayRecording legacy) {
        // Convert ReplayFrame to ReplayData
        List<ReplayData> convertedFrames = new ArrayList<>();
        for (ReplayFrame frame : legacy.frames) {
            ReplayData data = new ReplayData(legacy.recordedAt + (convertedFrames.size() * 50));
            
            // Convert player snapshots - skip for now since we need a Player object
            // This would need to be implemented differently for legacy compatibility
            
            convertedFrames.add(data);
        }
        
        return new EnhancedReplayRecording(
            legacy.gameId,
            legacy.gameType,
            legacy.minigameName,
            legacy.recordedAt,
            legacy.participants,
            convertedFrames,
            legacy.getSkinTextures()
        );
    }
}
