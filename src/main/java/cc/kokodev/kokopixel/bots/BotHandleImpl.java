package cc.kokodev.kokopixel.bots;

import cc.kokodev.kokopixel.api.bot.BotActions;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.bot.BotSenses;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * NMS-backed fake player that lives entirely in packet-space.
 * No real client is connected — it is purely visual, mirroring the replay system.
 * Packets are broadcast to every real player in the same world.
 */
public class BotHandleImpl implements BotHandle {

    private final UUID uuid;
    private final String name;
    private final JavaPlugin plugin;
    private final ServerPlayer nmsPlayer;
    private final World world;

    // Track who has already received the spawn packets for this bot
    private final Set<UUID> spawnedFor = new HashSet<>();

    private final BotSensesImpl senses;
    private final BotActionsImpl actions;

    public BotHandleImpl(String name, Location spawnAt, JavaPlugin plugin) {
        this.uuid = UUID.randomUUID();
        this.name = name;
        this.plugin = plugin;
        this.world = spawnAt.getWorld();

        ServerLevel level = ((CraftWorld) world).getHandle();
        GameProfile profile = new GameProfile(uuid, name);

        this.nmsPlayer = new ServerPlayer(
                ((CraftServer) plugin.getServer()).getServer(),
                level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());

        nmsPlayer.setPos(spawnAt.getX(), spawnAt.getY(), spawnAt.getZ());
        nmsPlayer.setYRot(spawnAt.getYaw());
        nmsPlayer.setXRot(spawnAt.getPitch());
        nmsPlayer.yHeadRot = spawnAt.getYaw();

        this.senses  = new BotSensesImpl(this);
        this.actions = new BotActionsImpl(this, senses);
    }

    // -------------------------------------------------------------------------
    // Spawn / despawn
    // -------------------------------------------------------------------------

    /** Spawn this bot for all currently online players in the same world. */
    public void spawnForWorld() {
        for (Player viewer : world.getPlayers()) {
            spawnFor(viewer);
        }
    }

    /** Spawn for a specific viewer (e.g. a player who just joined the game). */
    public void spawnFor(Player viewer) {
        if (spawnedFor.contains(viewer.getUniqueId())) return;
        spawnedFor.add(viewer.getUniqueId());
        sendSpawnPackets(viewer);
    }

    /** Despawn this bot for all viewers and clean up. */
    public void despawn() {
        broadcastToWorld(new ClientboundRemoveEntitiesPacket(nmsPlayer.getId()));
        broadcastToWorld(new ClientboundPlayerInfoRemovePacket(List.of(uuid)));
        spawnedFor.clear();
    }

    /** Despawn only for a specific viewer (e.g. a player leaving the game). */
    public void despawnFor(Player viewer) {
        if (!spawnedFor.remove(viewer.getUniqueId())) return;
        var conn = ((CraftPlayer) viewer).getHandle().connection;
        conn.send(new ClientboundRemoveEntitiesPacket(nmsPlayer.getId()));
        conn.send(new ClientboundPlayerInfoRemovePacket(List.of(uuid)));
    }

    // -------------------------------------------------------------------------
    // BotHandle implementation
    // -------------------------------------------------------------------------

    @Override public UUID getUniqueId() { return uuid; }
    @Override public String getName() { return name; }
    @Override public World getWorld() { return world; }
    @Override public BotSenses getSenses() { return senses; }
    @Override public BotActions getActions() { return actions; }

    @Override
    public Location getLocation() {
        return new Location(world, nmsPlayer.getX(), nmsPlayer.getY(), nmsPlayer.getZ(),
                nmsPlayer.getYRot(), nmsPlayer.getXRot());
    }
    @Override
    public void teleport(Location location) {
        nmsPlayer.setPos(location.getX(), location.getY(), location.getZ());
        nmsPlayer.setYRot(location.getYaw());
        nmsPlayer.setXRot(location.getPitch());
        nmsPlayer.yHeadRot = location.getYaw();
        broadcastToWorld(new ClientboundTeleportEntityPacket(nmsPlayer));
        broadcastToWorld(new ClientboundRotateHeadPacket(nmsPlayer, (byte) (location.getYaw() * 256f / 360f)));
    }

    @Override
    public void move(double dx, double dy, double dz, float yaw, float pitch) {
        // If delta > 8 blocks, fall back to absolute teleport
        if (Math.abs(dx) > 7.9 || Math.abs(dy) > 7.9 || Math.abs(dz) > 7.9) {
            teleport(new Location(world,
                    nmsPlayer.getX() + dx, nmsPlayer.getY() + dy, nmsPlayer.getZ() + dz, yaw, pitch));
            return;
        }
        nmsPlayer.setPos(nmsPlayer.getX() + dx, nmsPlayer.getY() + dy, nmsPlayer.getZ() + dz);
        nmsPlayer.setYRot(yaw);
        nmsPlayer.setXRot(pitch);
        nmsPlayer.yHeadRot = yaw;
        broadcastToWorld(new ClientboundMoveEntityPacket.PosRot(
                nmsPlayer.getId(),
                (short) (dx * 4096), (short) (dy * 4096), (short) (dz * 4096),
                (byte) (yaw * 256f / 360f),
                (byte) (pitch * 256f / 360f),
                true));
        broadcastToWorld(new ClientboundRotateHeadPacket(nmsPlayer, (byte) (yaw * 256f / 360f)));
    }

    @Override
    public void setHeadRotation(float yaw, float pitch) {
        nmsPlayer.setYRot(yaw);
        nmsPlayer.setXRot(pitch);
        nmsPlayer.yHeadRot = yaw;
        broadcastToWorld(new ClientboundMoveEntityPacket.Rot(
                nmsPlayer.getId(),
                (byte) (yaw * 256f / 360f),
                (byte) (pitch * 256f / 360f),
                true));
        broadcastToWorld(new ClientboundRotateHeadPacket(nmsPlayer, (byte) (yaw * 256f / 360f)));
    }

    @Override
    public void setSneaking(boolean sneaking) {
        nmsPlayer.setSharedFlag(1, sneaking);
        var dirty = nmsPlayer.getEntityData().packDirty();
        if (dirty != null) broadcastToWorld(new ClientboundSetEntityDataPacket(nmsPlayer.getId(), dirty));
    }

    @Override
    public void setSprinting(boolean sprinting) {
        nmsPlayer.setSharedFlag(3, sprinting);
        var dirty = nmsPlayer.getEntityData().packDirty();
        if (dirty != null) broadcastToWorld(new ClientboundSetEntityDataPacket(nmsPlayer.getId(), dirty));
    }

    @Override
    public void swingMainHand() {
        broadcastToWorld(new ClientboundAnimatePacket(nmsPlayer, 0));
    }

    @Override public void setHelmet(ItemStack item)     { sendEquipment(EquipmentSlot.HEAD, item); }
    @Override public void setChestplate(ItemStack item) { sendEquipment(EquipmentSlot.CHEST, item); }
    @Override public void setLeggings(ItemStack item)   { sendEquipment(EquipmentSlot.LEGS, item); }
    @Override public void setBoots(ItemStack item)      { sendEquipment(EquipmentSlot.FEET, item); }
    @Override public void setMainHand(ItemStack item)   { sendEquipment(EquipmentSlot.MAINHAND, item); }
    @Override public void setOffHand(ItemStack item)    { sendEquipment(EquipmentSlot.OFFHAND, item); }

    // -------------------------------------------------------------------------
    // NMS helpers
    // -------------------------------------------------------------------------

    private void sendEquipment(EquipmentSlot slot, ItemStack item) {
        net.minecraft.world.item.ItemStack nmsItem = item != null
                ? CraftItemStack.asNMSCopy(item)
                : net.minecraft.world.item.ItemStack.EMPTY;
        broadcastToWorld(new ClientboundSetEquipmentPacket(nmsPlayer.getId(),
                List.of(new com.mojang.datafixers.util.Pair<>(slot, nmsItem))));
    }

    private void sendSpawnPackets(Player viewer) {
        var conn = ((CraftPlayer) viewer).getHandle().connection;

        // Temporarily assign a stub connection so ClientboundPlayerInfoUpdatePacket
        // doesn't NPE when it tries to read latency from the ServerPlayer.
        net.minecraft.server.network.ServerGamePacketListenerImpl stubConn = null;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
            stubConn = (net.minecraft.server.network.ServerGamePacketListenerImpl)
                    unsafe.allocateInstance(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        } catch (Exception e) {
            plugin.getLogger().warning("[BotManager] Stub connection failed: " + e.getMessage());
        }

        nmsPlayer.connection = stubConn;
        try {
            conn.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(nmsPlayer)));
        } catch (Exception e) {
            plugin.getLogger().warning("[BotManager] PlayerInfo packet failed for " + name + ": " + e.getMessage());
        } finally {
            nmsPlayer.connection = null;
        }

        try {
            conn.send(new ClientboundAddEntityPacket(
                    nmsPlayer.getId(), uuid,
                    nmsPlayer.getX(), nmsPlayer.getY(), nmsPlayer.getZ(),
                    nmsPlayer.getXRot(), nmsPlayer.getYRot(),
                    net.minecraft.world.entity.EntityType.PLAYER,
                    0, nmsPlayer.getDeltaMovement(),
                    nmsPlayer.getYHeadRot()));

            // Make nametag visible (player entity nametags are hidden by default)
            var team = makeBotTeam();
            conn.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
            conn.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    team, name, ClientboundSetPlayerTeamPacket.Action.ADD));
        } catch (Exception e) {
            plugin.getLogger().warning("[BotManager] Spawn packet failed for " + name + ": " + e.getMessage());
        }
    }

    private net.minecraft.world.scores.PlayerTeam makeBotTeam() {
        var scoreboard = new net.minecraft.world.scores.Scoreboard();
        String teamName = ("bot_" + name).substring(0, Math.min(16, 4 + name.length()));
        var team = new net.minecraft.world.scores.PlayerTeam(scoreboard, teamName);
        team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.ALWAYS);
        team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);
        return team;
    }

    private void broadcastToWorld(net.minecraft.network.protocol.Packet<?> packet) {
        for (Player p : world.getPlayers()) {
            try {
                ((CraftPlayer) p).getHandle().connection.send(packet);
            } catch (Exception e) {
                plugin.getLogger().fine("[BotManager] Packet error for " + p.getName() + ": " + e.getMessage());
            }
        }
    }

    /** Called by BotManager when a new player joins the game world mid-match. */
    public void onPlayerJoinWorld(Player player) {
        spawnFor(player);
    }
}
