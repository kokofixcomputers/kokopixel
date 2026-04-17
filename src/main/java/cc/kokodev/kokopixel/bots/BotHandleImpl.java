package cc.kokodev.kokopixel.bots;

import cc.kokodev.kokopixel.api.bot.BotActions;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.bot.BotSenses;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftArmorStand;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * NMS-backed fake bot player.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li><b>Shadow ArmorStand</b> — a real, invisible, marker ArmorStand registered
 *       with the server. It owns the bot's canonical location so that any Bukkit API
 *       call that teleports "the player" (e.g. {@code player.teleport(loc)}) actually
 *       moves the stand, and we sync the visual NMS player to it every tick.</li>
 *   <li><b>NMS ServerPlayer</b> — packet-only, never added to the world entity list,
 *       never goes through login. Only used to construct and send spawn/move packets.</li>
 *   <li><b>CraftPlayer proxy</b> — the {@link #getBukkitPlayer()} result. It is the
 *       {@code CraftPlayer} of the NMS {@code ServerPlayer}. We add the {@code ServerPlayer}
 *       to the level's entity list via {@code addFreshEntityWithPassengers} so that
 *       {@code Bukkit.getEntity(uuid)} resolves, inventory operations work, and
 *       {@code p.teleport()} calls are intercepted and forwarded to the shadow stand.</li>
 * </ul>
 *
 * <p>Game plugins interact only with the {@link #getBukkitPlayer()} {@code Player} object.
 * They never need to know about the shadow stand.
 */
public class BotHandleImpl implements BotHandle {

    private final UUID uuid;
    private final String name;
    private final JavaPlugin plugin;
    private final ServerPlayer nmsPlayer;
    private final World world;

    /** The real server-managed anchor entity. Bukkit teleport calls go here. */
    private final ArmorStand shadowStand;

    /** Bot's current facing — maintained separately so sync task uses bot-driven rotation, not stand pose. */
    private float currentYaw   = 0f;
    private float currentPitch = 0f;

    /** Sync task — moves the NMS packet player to the stand every tick. */
    private BukkitTask syncTask;

    private final Set<UUID> spawnedFor = new HashSet<>();

    private final BotSensesImpl senses;
    private final BotActionsImpl actions;

    public BotHandleImpl(String name, Location spawnAt, JavaPlugin plugin) {
        this.uuid   = UUID.randomUUID();
        this.name   = name;
        this.plugin = plugin;
        this.world  = spawnAt.getWorld();

        this.currentYaw   = spawnAt.getYaw();
        this.currentPitch = 0f; // always start horizontal

        // ---- Shadow ArmorStand (real entity, fully server-managed) ----
        this.shadowStand = spawnShadowStand(spawnAt);

        // ---- NMS ServerPlayer (packet-only visual) ----
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

        // Do NOT add to the level entity list — Paper hooks waypoints, scoreboards,
        // and disconnect handlers onto registered players, all of which require a real
        // connection.  The NMS ServerPlayer here is purely a packet-data object; the
        // shadow ArmorStand is the only thing the server actually tracks.

        this.senses  = new BotSensesImpl(this);
        this.actions = new BotActionsImpl(this, senses);

        startSyncTask();
    }

    // -------------------------------------------------------------------------
    // Shadow stand helpers
    // -------------------------------------------------------------------------

    private ArmorStand spawnShadowStand(Location loc) {
        ArmorStand stand = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setCollidable(false);
        stand.setInvulnerable(false);
        stand.setMarker(false);        // needs hitbox for damage events
        stand.setSmall(false);         // full-size for a player-sized hitbox
        stand.setCustomNameVisible(false);
        stand.setPersistent(false);
        stand.addScoreboardTag("kp_bot_shadow");
        stand.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(1024);
        stand.setHealth(1024);
        return stand;
    }

    /**
     * Every tick: read stand's position and broadcast a move packet for the NMS player.
     * This means any Bukkit code that calls {@code player.teleport(loc)} on the CraftPlayer
     * will — through our override — teleport the stand, and within one tick the visual
     * representation follows.
     */
    private void startSyncTask() {
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (shadowStand.isDead()) return;
            Location loc = shadowStand.getLocation();
            double nx = loc.getX(), ny = loc.getY(), nz = loc.getZ();
            // Use bot-driven yaw/pitch, NOT the stand's pose rotation
            float yaw   = currentYaw;
            float pitch = currentPitch;

            double dx = nx - nmsPlayer.getX();
            double dy = ny - nmsPlayer.getY();
            double dz = nz - nmsPlayer.getZ();

            nmsPlayer.setPos(nx, ny, nz);
            nmsPlayer.setYRot(yaw);
            nmsPlayer.setXRot(pitch);
            nmsPlayer.yHeadRot = yaw;

            if (Math.abs(dx) > 0.001 || Math.abs(dy) > 0.001 || Math.abs(dz) > 0.001) {
                // Clamp deltas to the max the PosRot packet supports (±8 blocks per tick)
                // For larger jumps (teleports) split into a full-position sync by
                // sending a PosRot with the clamped value — the NMS position is
                // already set correctly so the client stays in sync.
                double cx = Math.max(-7.9, Math.min(7.9, dx));
                double cy = Math.max(-7.9, Math.min(7.9, dy));
                double cz = Math.max(-7.9, Math.min(7.9, dz));
                broadcastToWorld(new ClientboundMoveEntityPacket.PosRot(
                        nmsPlayer.getId(),
                        (short)(cx * 4096), (short)(cy * 4096), (short)(cz * 4096),
                        (byte)(yaw * 256f / 360f), (byte)(pitch * 256f / 360f), true));
            }
            broadcastToWorld(new ClientboundRotateHeadPacket(nmsPlayer, (byte)(yaw * 256f / 360f)));
        }, 1L, 1L);
    }

    // -------------------------------------------------------------------------
    // Spawn / despawn
    // -------------------------------------------------------------------------

    public void spawnForWorld() {
        for (Player viewer : world.getPlayers()) spawnFor(viewer);
    }

    public void spawnFor(Player viewer) {
        if (spawnedFor.contains(viewer.getUniqueId())) return;
        spawnedFor.add(viewer.getUniqueId());
        sendSpawnPackets(viewer);
    }

    public void despawn() {
        if (syncTask != null) { syncTask.cancel(); syncTask = null; }
        broadcastToWorld(new ClientboundRemoveEntitiesPacket(nmsPlayer.getId()));
        broadcastToWorld(new ClientboundPlayerInfoRemovePacket(List.of(uuid)));
        spawnedFor.clear();
        shadowStand.remove();
    }

    public void despawnFor(Player viewer) {
        if (!spawnedFor.remove(viewer.getUniqueId())) return;
        var conn = ((CraftPlayer) viewer).getHandle().connection;
        conn.send(new ClientboundRemoveEntitiesPacket(nmsPlayer.getId()));
        conn.send(new ClientboundPlayerInfoRemovePacket(List.of(uuid)));
    }

    public void onPlayerJoinWorld(Player player) { spawnFor(player); }

    // -------------------------------------------------------------------------
    // BotHandle — identity
    // -------------------------------------------------------------------------

    @Override public UUID getUniqueId() { return uuid; }
    @Override public String getName()   { return name; }
    @Override public World getWorld()   { return world; }
    @Override public BotSenses getSenses()  { return senses; }
    @Override public BotActions getActions() { return actions; }

    /**
     * Returns the {@code CraftPlayer} for this bot.
     *
     * <p>Game plugins use this as a normal {@code Player}:
     * <ul>
     *   <li>{@code getInventory()} — backed by the NMS player's real inventory container</li>
     *   <li>{@code getLocation()} — reads from the shadow ArmorStand</li>
     *   <li>{@code teleport(loc)} — moves the shadow ArmorStand (visual follows within 1 tick)</li>
     *   <li>{@code setGameMode / setHealth / sendMessage} — no-op or stored silently</li>
     * </ul>
     */
    public Player getBukkitPlayer() {
        return (Player) nmsPlayer.getBukkitEntity();
    }

    /** Returns the entity UUID of the shadow ArmorStand for damage routing. */
    public UUID getShadowStandUUID() {
        return shadowStand.getUniqueId();
    }

    // -------------------------------------------------------------------------
    // BotHandle — location (reads from shadow stand, the authoritative position)
    // -------------------------------------------------------------------------

    @Override
    public Location getLocation() {
        Location loc = shadowStand.getLocation();
        return new Location(world, loc.getX(), loc.getY(), loc.getZ(), currentYaw, currentPitch);
    }

    // -------------------------------------------------------------------------
    // BotHandle — movement (moves the shadow stand; sync task propagates visually)
    // -------------------------------------------------------------------------

    @Override
    public void teleport(Location location) {
        currentYaw   = location.getYaw();
        currentPitch = 0f; // keep horizontal
        shadowStand.teleport(location);
    }

    @Override
    public void move(double dx, double dy, double dz, float yaw, float pitch) {
        currentYaw   = yaw;
        currentPitch = 0f;
        Location here = shadowStand.getLocation();
        shadowStand.teleport(new Location(world,
                here.getX() + dx, here.getY() + dy, here.getZ() + dz, yaw, pitch));
    }

    @Override
    public void setHeadRotation(float yaw, float pitch) {
        currentYaw   = yaw;
        currentPitch = pitch;
        // Don't move the stand — just update rotation for next sync tick
    }

    // -------------------------------------------------------------------------
    // BotHandle — state / animations (NMS packets only)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // BotHandle — equipment (visual packets + NMS inventory so getInventory works)
    // -------------------------------------------------------------------------

    @Override
    public void setHelmet(ItemStack item)     { setEquip(EquipmentSlot.HEAD, item); }
    @Override
    public void setChestplate(ItemStack item) { setEquip(EquipmentSlot.CHEST, item); }
    @Override
    public void setLeggings(ItemStack item)   { setEquip(EquipmentSlot.LEGS, item); }
    @Override
    public void setBoots(ItemStack item)      { setEquip(EquipmentSlot.FEET, item); }
    @Override
    public void setMainHand(ItemStack item)   { setEquip(EquipmentSlot.MAINHAND, item); }
    @Override
    public void setOffHand(ItemStack item)    { setEquip(EquipmentSlot.OFFHAND, item); }

    private void setEquip(EquipmentSlot slot, ItemStack item) {
        // Store on the NMS inventory so CraftPlayer.getInventory() reflects it
        net.minecraft.world.item.ItemStack nmsItem = item != null
                ? CraftItemStack.asNMSCopy(item)
                : net.minecraft.world.item.ItemStack.EMPTY;
        switch (slot) {
            case HEAD     -> nmsPlayer.getInventory().setItem(103, nmsItem);
            case CHEST    -> nmsPlayer.getInventory().setItem(102, nmsItem);
            case LEGS     -> nmsPlayer.getInventory().setItem(101, nmsItem);
            case FEET     -> nmsPlayer.getInventory().setItem(100, nmsItem);
            case MAINHAND -> {
                // slot 0 is the default held slot for a ServerPlayer with no prior selection change
                nmsPlayer.getInventory().setItem(0, nmsItem);
            }
            case OFFHAND  -> nmsPlayer.getInventory().setItem(40, nmsItem);
            default -> {}
        }
        // Broadcast visual packet
        broadcastToWorld(new ClientboundSetEquipmentPacket(nmsPlayer.getId(),
                List.of(new com.mojang.datafixers.util.Pair<>(slot, nmsItem))));
    }

    // -------------------------------------------------------------------------
    // NMS packet helpers
    // -------------------------------------------------------------------------

    private void sendSpawnPackets(Player viewer) {
        var conn = ((CraftPlayer) viewer).getHandle().connection;

        // Stub connection so PlayerInfoUpdate doesn't NPE on latency read
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
                    0, nmsPlayer.getDeltaMovement(), nmsPlayer.getYHeadRot()));

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
}
