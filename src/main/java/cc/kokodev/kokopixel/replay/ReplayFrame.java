package cc.kokodev.kokopixel.replay;

import java.io.*;
import java.util.*;

/**
 * One tick of recorded game state.
 * Stores every tracked player's position/state and any block changes that happened this tick.
 */
public class ReplayFrame implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Per-player snapshot for this tick. */
    public final Map<UUID, PlayerSnapshot> players = new LinkedHashMap<>();
    /** Block changes that occurred this tick: key = "x,y,z", value = Material name. */
    public final List<BlockChange> blockChanges = new ArrayList<>();

    public static class PlayerSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final double x, y, z;
        public final float yaw, pitch;
        public final String heldItem;   // Material name or "AIR"
        public final boolean sneaking;
        public final boolean sprinting;
        public final boolean onGround;

        public PlayerSnapshot(String name, double x, double y, double z,
                              float yaw, float pitch, String heldItem,
                              boolean sneaking, boolean sprinting, boolean onGround) {
            this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.heldItem = heldItem;
            this.sneaking = sneaking;
            this.sprinting = sprinting;
            this.onGround = onGround;
        }
    }

    public static class BlockChange implements Serializable {
        private static final long serialVersionUID = 1L;
        public final int x, y, z;
        public final String material; // "AIR" for break, material name for place

        public BlockChange(int x, int y, int z, String material) {
            this.x = x; this.y = y; this.z = z;
            this.material = material;
        }
    }
}
