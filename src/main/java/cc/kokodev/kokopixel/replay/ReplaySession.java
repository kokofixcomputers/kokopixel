package cc.kokodev.kokopixel.replay;

import cc.kokodev.kokopixel.KokoPixel;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Manages one active replay playback.
 * Uses NMS ServerPlayer entities sent client-side only via packets.
 * Paper 1.20.5+ exposes craftbukkit without version suffix.
 */
public class ReplaySession {

    private final KokoPixel plugin;
    private final ReplayRecording recording;
    private final World replayWorld;
    private final Set<UUID> viewers = new LinkedHashSet<>();

    private final Map<UUID, ServerPlayer> fakePlayers = new LinkedHashMap<>();
    private final Map<UUID, ReplayFrame.PlayerSnapshot> lastSnapshot = new HashMap<>();

    private int currentFrame = 0;
    private BukkitTask tickTask;
    private boolean finished = false;

    public ReplaySession(KokoPixel plugin, ReplayRecording recording, World replayWorld) {
        this.plugin = plugin;
        this.recording = recording;
        this.replayWorld = replayWorld;
    }

    // -------------------------------------------------------------------------
    // Viewer management
    // -------------------------------------------------------------------------

    public void addViewer(Player viewer) {
        viewers.add(viewer.getUniqueId());
        plugin.getSpectatorManager().enableSpectator(viewer, true);
        viewer.teleport(replayWorld.getSpawnLocation());
        viewer.setMetadata("kp_replay_session",
                new org.bukkit.metadata.FixedMetadataValue(plugin, recording.gameId.toString()));
        viewer.sendMessage("§7[§dReplay§7] §eWatching §f" + recording.gameType
                + " §7(" + recording.durationSeconds() + "s) — §bUse bed to exit.");
        for (ServerPlayer fake : fakePlayers.values()) sendSpawnPackets(viewer, fake);
    }

    public void removeViewer(Player viewer) {
        viewers.remove(viewer.getUniqueId());
        viewer.removeMetadata("kp_replay_session", plugin);
        if (plugin.getSpectatorManager().isSpectator(viewer))
            plugin.getSpectatorManager().disableSpectator(viewer);
        viewer.teleport(plugin.getLobbySpawn());
        plugin.getGameSelectorMenu().giveGameSelector(viewer);
        viewer.sendMessage("§7[§dReplay§7] §cLeft replay.");
        if (viewers.isEmpty()) stop();
    }

    public boolean hasViewer(UUID id) { return viewers.contains(id); }
    public boolean isFinished() { return finished; }
    public UUID getRecordingId() { return recording.gameId; }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

    public void start() {
        if (!recording.frames.isEmpty()) {
            for (Map.Entry<UUID, ReplayFrame.PlayerSnapshot> entry
                    : recording.frames.get(0).players.entrySet()) {
                spawnFakePlayer(entry.getKey(), entry.getValue());
            }
        }
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (currentFrame >= recording.frames.size()) { stop(); return; }

        ReplayFrame frame = recording.frames.get(currentFrame++);

        // Apply block changes to the replay world
        for (ReplayFrame.BlockChange bc : frame.blockChanges) {
            Material mat = Material.matchMaterial(bc.material);
            replayWorld.getBlockAt(bc.x, bc.y, bc.z).setType(mat != null ? mat : Material.AIR, false);
        }

        // Update fake players
        for (Map.Entry<UUID, ReplayFrame.PlayerSnapshot> entry : frame.players.entrySet()) {
            UUID id = entry.getKey();
            ReplayFrame.PlayerSnapshot snap = entry.getValue();
            if (!fakePlayers.containsKey(id)) spawnFakePlayer(id, snap);

            ServerPlayer fake = fakePlayers.get(id);
            if (fake == null) continue;

            ReplayFrame.PlayerSnapshot prev = lastSnapshot.get(id);
            lastSnapshot.put(id, snap);

            // Movement
            double dx = snap.x - (prev != null ? prev.x : snap.x);
            double dy = snap.y - (prev != null ? prev.y : snap.y);
            double dz = snap.z - (prev != null ? prev.z : snap.z);
            boolean moved = prev == null || dx != 0 || dy != 0 || dz != 0;
            boolean rotated = prev == null || snap.yaw != prev.yaw || snap.pitch != prev.pitch;

            if (moved || rotated) {
                broadcastPacket(new ClientboundMoveEntityPacket.PosRot(
                        fake.getId(),
                        (short) (dx * 4096), (short) (dy * 4096), (short) (dz * 4096),
                        (byte) (snap.yaw * 256f / 360f),
                        (byte) (snap.pitch * 256f / 360f),
                        snap.onGround));
                broadcastPacket(new ClientboundRotateHeadPacket(
                        fake, (byte) (snap.yaw * 256f / 360f)));
            }

            // Held item
            Material heldMat = Material.matchMaterial(snap.heldItem);
            net.minecraft.world.item.ItemStack nmsItem = (heldMat != null && heldMat != Material.AIR)
                    ? CraftItemStack.asNMSCopy(new org.bukkit.inventory.ItemStack(heldMat))
                    : net.minecraft.world.item.ItemStack.EMPTY;
            broadcastPacket(new ClientboundSetEquipmentPacket(fake.getId(),
                    List.of(new com.mojang.datafixers.util.Pair<>(EquipmentSlot.MAINHAND, nmsItem))));

            // Sneaking / sprinting metadata
            if (prev == null || snap.sneaking != prev.sneaking || snap.sprinting != prev.sprinting) {
                // Mutate the fake player's flags directly so getEntityData() reflects them
                byte flags = 0;
                if (snap.sneaking)  flags |= 0x02;
                if (snap.sprinting) flags |= 0x08;
                fake.setSharedFlag(1, snap.sneaking);   // flag index 1 = crouching
                fake.setSharedFlag(3, snap.sprinting);  // flag index 3 = sprinting
                broadcastPacket(new ClientboundSetEntityDataPacket(
                        fake.getId(), fake.getEntityData().packDirty()));
            }
        }

        // Despawn players no longer in this frame
        new HashSet<>(fakePlayers.keySet()).stream()
                .filter(id -> !frame.players.containsKey(id))
                .forEach(this::despawnFakePlayer);

        // Progress bar via XP
        float progress = (float) currentFrame / recording.frames.size();
        for (UUID vid : viewers) {
            Player v = plugin.getServer().getPlayer(vid);
            if (v != null) v.setExp(Math.min(progress, 1f));
        }
    }

    public void stop() {
        finished = true;
        if (tickTask != null) tickTask.cancel();
        new ArrayList<>(fakePlayers.keySet()).forEach(this::despawnFakePlayer);
        new ArrayList<>(viewers).forEach(vid -> {
            Player v = plugin.getServer().getPlayer(vid);
            if (v != null) removeViewer(v);
        });
        plugin.getWorldManager().deleteWorld(replayWorld);
        plugin.getReplayManager().onSessionEnd(this);
    }

    // -------------------------------------------------------------------------
    // NMS fake player helpers
    // -------------------------------------------------------------------------

    private void spawnFakePlayer(UUID id, ReplayFrame.PlayerSnapshot snap) {
        try {
            ServerLevel level = ((CraftWorld) replayWorld).getHandle();
            GameProfile profile = new GameProfile(id, snap.name);
            ServerPlayer fake = new ServerPlayer(
                    ((CraftServer) plugin.getServer()).getServer(),
                    level, profile,
                    net.minecraft.server.level.ClientInformation.createDefault());
            fake.setPos(snap.x, snap.y, snap.z);
            fake.setYRot(snap.yaw);
            fake.setXRot(snap.pitch);
            fakePlayers.put(id, fake);
            lastSnapshot.put(id, snap);
            for (UUID vid : viewers) {
                Player v = plugin.getServer().getPlayer(vid);
                if (v != null) sendSpawnPackets(v, fake);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to spawn fake player " + snap.name + ": " + e.getMessage());
        }
    }

    private void despawnFakePlayer(UUID id) {
        ServerPlayer fake = fakePlayers.remove(id);
        lastSnapshot.remove(id);
        if (fake == null) return;
        broadcastPacket(new ClientboundRemoveEntitiesPacket(fake.getId()));
    }

    private void sendSpawnPackets(Player viewer, ServerPlayer fake) {
        var conn = ((CraftPlayer) viewer).getHandle().connection;
        conn.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
        // Build the spawn packet from stable fields — avoids the constructor signature
        // change between 1.21.1 and 1.21.2 while remaining compatible across all 1.21.x.
        conn.send(new ClientboundAddEntityPacket(
                fake.getId(),
                fake.getUUID(),
                fake.getX(), fake.getY(), fake.getZ(),
                fake.getXRot(), fake.getYRot(),
                net.minecraft.world.entity.EntityType.PLAYER,
                0,
                fake.getDeltaMovement(),
                fake.getYHeadRot()));
    }

    private void broadcastPacket(net.minecraft.network.protocol.Packet<?> packet) {
        for (UUID vid : viewers) {
            Player v = plugin.getServer().getPlayer(vid);
            if (v != null) ((CraftPlayer) v).getHandle().connection.send(packet);
        }
    }
}
