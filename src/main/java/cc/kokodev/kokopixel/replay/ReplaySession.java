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
    private double replaySpeed = 1.0; // default to normal speed
    private BukkitTask tickTask;
    private boolean finished = false;
    private boolean paused = false;

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
        viewer.setMetadata("kp_replay_session",
                new org.bukkit.metadata.FixedMetadataValue(plugin, recording.gameId.toString()));

        // Teleport to the first recorded player position so the viewer is near the action.
        // Fall back to world spawn if no frames exist yet.
        org.bukkit.Location startLoc = replayWorld.getSpawnLocation();
        if (!recording.frames.isEmpty() && !recording.frames.get(0).players.isEmpty()) {
            ReplayFrame.PlayerSnapshot first = recording.frames.get(0).players.values().iterator().next();
            startLoc = new org.bukkit.Location(replayWorld, first.x, first.y + 3, first.z);
        }
        viewer.teleport(startLoc);

        // Give spectators a green dye to start
        org.bukkit.inventory.ItemStack dye = new org.bukkit.inventory.ItemStack(Material.GREEN_DYE, 1);
        org.bukkit.inventory.meta.ItemMeta meta = dye.getItemMeta();
        meta.setDisplayName("Pause/Resume Replay");
        dye.setItemMeta(meta);
        viewer.getInventory().addItem(dye);

        viewer.sendMessage("§7[§dReplay§7] §eWatching §f" + recording.gameType
                + " §7(" + recording.durationSeconds() + "s) — §bUse bed to exit.");
        for (ServerPlayer fake : fakePlayers.values()) sendSpawnPackets(viewer, fake, fake.getGameProfile().getName());
    }

    public void removeViewer(Player viewer) {
        viewers.remove(viewer.getUniqueId());
        viewer.removeMetadata("kp_replay_session", plugin);
        if (plugin.getSpectatorManager().isSpectator(viewer))
            plugin.getSpectatorManager().disableSpectator(viewer);
        // viewer.teleport(plugin.getLobbySpawn());
        plugin.getGameSelectorMenu().giveGameSelector(viewer);
        viewer.sendMessage("§7[§dReplay§7] §cLeft replay.");
        if (viewers.isEmpty()) stop();
    }

    public boolean hasViewer(UUID id) { return viewers.contains(id); }
    public boolean isPaused() { return paused; }
    public void pause() { paused = true; tickTask.cancel(); }
    public void resume() { if (paused) { paused = false; tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); } }

    public boolean isFinished() { return finished; }
    public UUID getRecordingId() { return recording.gameId; }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

    public void start() {
        plugin.getLogger().info("[Replay] Starting session for " + recording.gameType
                + " — " + recording.frames.size() + " frames, "
                + recording.participants.size() + " participants.");

        if (recording.frames.isEmpty()) {
            plugin.getLogger().warning("[Replay] Recording has no frames — nothing to play back.");
            for (UUID vid : viewers) {
                Player v = plugin.getServer().getPlayer(vid);
                if (v != null) v.sendMessage("§c[Replay] This recording is empty.");
            }
            stop();
            return;
        }

        ReplayFrame first = recording.frames.get(0);
        plugin.getLogger().info("[Replay] First frame has " + first.players.size() + " player(s).");

        for (Map.Entry<UUID, ReplayFrame.PlayerSnapshot> entry : first.players.entrySet()) {
            spawnFakePlayer(entry.getKey(), entry.getValue());
        }
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (paused || currentFrame >= recording.frames.size()) { return; }

        int frameAdvance = (int) Math.max(1, replaySpeed); // Calculate frame steps based on speed multiplier
currentFrame += frameAdvance; // Increase current frame by calculated steps
ReplayFrame frame = recording.frames.get(currentFrame);

        // Debug logging for first few frames to compare with recording
        if (currentFrame <= 5) {
            for (Map.Entry<UUID, ReplayFrame.PlayerSnapshot> entry : frame.players.entrySet()) {
                ReplayFrame.PlayerSnapshot snap = entry.getValue();
                plugin.getLogger().info("[Replay Debug] Playback Frame " + currentFrame + " - Player " + snap.name + 
                    " at (" + snap.x + ", " + snap.y + ", " + snap.z + ")");
            }
        }

        // Apply block changes to the replay world
        for (ReplayFrame.BlockChange bc : frame.blockChanges) {
            Material mat = Material.matchMaterial(bc.material);
            replayWorld.getBlockAt(bc.x, bc.y, bc.z).setType(mat != null ? mat : Material.AIR, false);
        }

        // Handle custom death events
        for (ReplayFrame.DeathEvent death : frame.deathEvents) {
            handleDeathEvent(death);
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

            boolean moved = prev == null || snap.x != prev.x || snap.y != prev.y || snap.z != prev.z;
            boolean rotated = prev == null || snap.yaw != prev.yaw || snap.pitch != prev.pitch;

            if (moved || rotated) {
                double prevX = prev != null ? prev.x : snap.x;
                double prevY = prev != null ? prev.y : snap.y;
                double prevZ = prev != null ? prev.z : snap.z;
                
                double dx = snap.x - prevX;
                double dy = snap.y - prevY;
                double dz = snap.z - prevZ;

                // Always use absolute positioning for more accuracy, especially for Z-axis
                // This prevents cumulative errors from relative movement
                // Use absolute positioning every 10 ticks to prevent drift
                boolean needsTeleport = prev == null || currentFrame % 10 == 0 || 
                    Math.abs(dx) > 7.9 || Math.abs(dy) > 7.9 || Math.abs(dz) > 7.9;

                if (needsTeleport) {
                    // Use absolute positioning for accuracy
                    fake.setPos(snap.x, snap.y, snap.z);
                    fake.setYRot(snap.yaw);
                    fake.setXRot(snap.pitch);
                    
                    // Send teleport packet to update client position
                    broadcastPacket(new ClientboundTeleportEntityPacket(fake));
                } else {
                    // Use relative movement for small changes
                    broadcastPacket(new ClientboundMoveEntityPacket.PosRot(
                            fake.getId(),
                            (short) (dx * 4096), (short) (dy * 4096), (short) (dz * 4096),
                            (byte) (snap.yaw * 256f / 360f),
                            (byte) (snap.pitch * 256f / 360f),
                            snap.onGround));
                }
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
            
            // Handle game mode changes, flight, invulnerability, etc.
            if (prev == null || 
                !snap.gameMode.equals(prev.gameMode) ||
                snap.allowFlight != prev.allowFlight ||
                snap.flying != prev.flying ||
                snap.invulnerable != prev.invulnerable ||
                snap.collidable != prev.collidable ||
                snap.canPickupItems != prev.canPickupItems ||
                !snap.potionEffects.equals(prev.potionEffects)) {
                
                // Update fake player metadata to reflect state changes
                updateFakePlayerMetadata(fake, snap, prev);
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
        // Note: This method would need to be updated to work with EnhancedReplayManager
        // For now, we'll comment it out to avoid compilation issues
        // plugin.getReplayManager().onSessionEnd(this);
    }

    // -------------------------------------------------------------------------
    // NMS fake player helpers
    // -------------------------------------------------------------------------

    private void spawnFakePlayer(UUID id, ReplayFrame.PlayerSnapshot snap) {
        plugin.getLogger().info("[Replay] Spawning fake player: " + snap.name
                + " at " + snap.x + "," + snap.y + "," + snap.z
                + " viewers=" + viewers.size());
        try {
            ServerLevel level = ((CraftWorld) replayWorld).getHandle();
            UUID fakeUUID = new UUID(id.getMostSignificantBits() ^ 0xDEADBEEFL, id.getLeastSignificantBits());

            // Build a GameProfile with skin textures already in it.
            // GameProfile's property map is immutable after construction, so we must
            // either copy from a real player's already-populated profile, or build
            // a mutable PropertyMap and pass it to the constructor.
            GameProfile profile = buildProfileWithSkin(fakeUUID, snap.name, id);

            ServerPlayer fake = new ServerPlayer(
                    ((CraftServer) plugin.getServer()).getServer(),
                    level, profile,
                    net.minecraft.server.level.ClientInformation.createDefault());
            fake.setPos(snap.x, snap.y, snap.z);
            fake.setYRot(snap.yaw);
            fake.setXRot(snap.pitch);
            fakePlayers.put(id, fake);
            lastSnapshot.put(id, snap);
            plugin.getLogger().info("[Replay] Fake player created, sending packets to "
                    + viewers.size() + " viewer(s).");
            for (UUID vid : viewers) {
                Player v = plugin.getServer().getPlayer(vid);
                if (v != null) sendSpawnPackets(v, fake, snap.name);
                else plugin.getLogger().warning("[Replay] Viewer " + vid + " not online.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to spawn fake player " + snap.name
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Builds a GameProfile for the fake player, with skin textures populated.
     * Strategy: if the real player is online, copy their already-authenticated NMS profile
     * (which has a mutable PropertyMap populated during login). Otherwise create a bare profile.
     */
    private GameProfile buildProfileWithSkin(UUID fakeUUID, String name, UUID originalId) {
        // Best case: real player is online — their NMS profile has textures already loaded
        Player realPlayer = plugin.getServer().getPlayer(originalId);
        if (realPlayer != null) {
            GameProfile realProfile = ((CraftPlayer) realPlayer).getHandle().getGameProfile();
            // Create a new profile with the fake UUID but copy the real profile's properties
            // by cloning via the (UUID, String, PropertyMap) constructor if available,
            // otherwise fall back to a bare profile.
            try {
                Class<?> propMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
                // Try GameProfile(UUID, String, PropertyMap)
                GameProfile newProfile = GameProfile.class
                        .getConstructor(java.util.UUID.class, String.class, propMapClass)
                        .newInstance(fakeUUID, name, getPropertiesMap(realProfile));
                return newProfile;
            } catch (NoSuchMethodException ignored) {
                // Constructor doesn't exist — create bare profile and try mutable copy
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Profile copy failed: " + e.getMessage());
            }
            // Fallback: create bare profile — skin won't show but at least player spawns
            return new GameProfile(fakeUUID, name);
        }

        // Stored skin path — create bare profile (immutable map, can't inject textures)
        // TODO: when offline, skin defaults to Steve. Future improvement: store full profile.
        return new GameProfile(fakeUUID, name);
    }

    private void despawnFakePlayer(UUID id) {
        ServerPlayer fake = fakePlayers.remove(id);
        ReplayFrame.PlayerSnapshot snap = lastSnapshot.remove(id);
        if (fake == null) return;

        // Remove entity from client view
        broadcastPacket(new ClientboundRemoveEntitiesPacket(fake.getId()));

        // Remove from tab list so the client doesn't show a ghost entry
        broadcastPacket(new ClientboundPlayerInfoRemovePacket(List.of(fake.getUUID())));

        // Remove the nametag team
        if (snap != null) {
            String teamName = ("rp_" + snap.name).substring(0,
                    Math.min(16, ("rp_" + snap.name).length()));
            var scoreboard = new net.minecraft.world.scores.Scoreboard();
            var team = new net.minecraft.world.scores.PlayerTeam(scoreboard, teamName);
            broadcastPacket(ClientboundSetPlayerTeamPacket.createRemovePacket(team));
        }
    }

    private void sendSpawnPackets(Player viewer, ServerPlayer fake, String playerName) {
        var conn = ((CraftPlayer) viewer).getHandle().connection;

        net.minecraft.server.network.ServerGamePacketListenerImpl stubConn = null;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
            stubConn = (net.minecraft.server.network.ServerGamePacketListenerImpl)
                    unsafe.allocateInstance(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Stub connection failed: " + e.getMessage());
        }

        fake.connection = stubConn;
        try {
            conn.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
            plugin.getLogger().info("[Replay] Sent player info packet for " + playerName);
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Player info packet failed for "
                    + playerName + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            fake.connection = null;
        }

        try {
            conn.send(new ClientboundAddEntityPacket(
                    fake.getId(),
                    fake.getUUID(),
                    fake.getX(), fake.getY(), fake.getZ(),
                    fake.getXRot(), fake.getYRot(),
                    net.minecraft.world.entity.EntityType.PLAYER,
                    0,
                    fake.getDeltaMovement(),
                    fake.getYHeadRot()));
            plugin.getLogger().info("[Replay] Sent spawn packet for " + playerName);

            // Force nametag visibility — player entity nametags are hidden by default.
            // Create a scoreboard team with nameTagVisibility=always and add the fake player.
            conn.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(
                    makeReplayTeam(playerName), true));
            conn.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    makeReplayTeam(playerName),
                    playerName,
                    ClientboundSetPlayerTeamPacket.Action.ADD));
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Spawn packet failed for "
                    + playerName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Creates a PlayerTeam with nameTagVisibility=always for the fake player.
     * Each fake player gets their own team so names don't interfere with each other.
     * The team name is truncated to 16 chars (Minecraft limit).
     */
    private net.minecraft.world.scores.PlayerTeam makeReplayTeam(String playerName) {
        var scoreboard = new net.minecraft.world.scores.Scoreboard();
        String teamName = ("rp_" + playerName).substring(0,
                Math.min(16, ("rp_" + playerName).length()));
        var team = new net.minecraft.world.scores.PlayerTeam(scoreboard, teamName);
        team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.ALWAYS);
        team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);
        return team;
    }

    /** Finds and invokes the properties accessor on a GameProfile (handles renamed methods). */
    private Object getPropertiesMap(GameProfile profile) {
        for (java.lang.reflect.Method m : profile.getClass().getMethods()) {
            if ((m.getName().equals("getProperties") || m.getName().equals("properties"))
                    && m.getParameterCount() == 0) {
                try { return m.invoke(profile); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void handleDeathEvent(ReplayFrame.DeathEvent death) {
        // Send death message to all viewers
        String message = death.deathMessage;
        if (message == null || message.isEmpty()) {
            message = "§c" + death.playerName + " §7died.";
        }
        
        for (UUID vid : viewers) {
            Player viewer = plugin.getServer().getPlayer(vid);
            if (viewer != null) {
                viewer.sendMessage(message);
                
                // Show special effects for elimination (like BedWars)
                if (death.eliminated) {
                    viewer.sendActionBar("§c§l" + death.playerName + " ELIMINATED!");
                    viewer.playSound(viewer.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                } else if (!death.hasBed) {
                    viewer.sendActionBar("§c" + death.playerName + "'s bed was destroyed!");
                    viewer.playSound(viewer.getLocation(), org.bukkit.Sound.ENTITY_WITHER_BREAK_BLOCK, 0.5f, 1.0f);
                }
            }
        }
        
        plugin.getLogger().info("[Replay] Death event: " + death.playerName + " - " + death.deathCause + 
            (death.eliminated ? " (ELIMINATED)" : death.hasBed ? " (has bed)" : " (no bed)"));
    }

    private int getEffectId(org.bukkit.potion.PotionEffectType type) {
        // Map Bukkit potion effect types to NMS effect IDs
        if (type == null) return 0;
        
        switch (type.getName()) {
            case "SPEED": return 1;
            case "SLOW": return 2;
            case "FAST_DIGGING": return 3;
            case "SLOW_DIGGING": return 4;
            case "INCREASE_DAMAGE": return 5;
            case "HEAL": return 6;
            case "HARM": return 7;
            case "JUMP": return 8;
            case "CONFUSION": return 9;
            case "REGENERATION": return 10;
            case "DAMAGE_RESISTANCE": return 11;
            case "FIRE_RESISTANCE": return 12;
            case "WATER_BREATHING": return 13;
            case "INVISIBILITY": return 14;
            case "BLINDNESS": return 15;
            case "NIGHT_VISION": return 16;
            case "HUNGER": return 17;
            case "WEAKNESS": return 18;
            case "POISON": return 19;
            case "WITHER": return 20;
            case "HEALTH_BOOST": return 21;
            case "ABSORPTION": return 22;
            case "SATURATION": return 23;
            case "GLOWING": return 24;
            case "LEVITATION": return 25;
            case "LUCK": return 26;
            case "UNLUCK": return 27;
            case "SLOW_FALLING": return 28;
            case "CONDUIT_POWER": return 29;
            case "DOLPHINS_GRACE": return 30;
            case "BAD_OMEN": return 31;
            case "HERO_OF_THE_VILLAGE": return 32;
            default: return 0;
        }
    }

    private void updateFakePlayerMetadata(ServerPlayer fake, ReplayFrame.PlayerSnapshot snap, ReplayFrame.PlayerSnapshot prev) {
        // Update invisibility effect (this is the most important one for obbedwars-style spectator)
        boolean hasInvisibility = snap.potionEffects.stream()
            .anyMatch(effect -> effect.type.equals("INVISIBILITY"));
        
        // Update entity metadata flags for invisibility and other states
        int data = 0;
        if (hasInvisibility) data |= 0x20; // invisibility flag
        if (snap.flying) data |= 0x02; // gliding/elytra flag (used for flying effect)
        if (snap.invulnerable) data |= 0x01; // on fire flag (repurposed for invulnerable)
        
        broadcastPacket(new ClientboundSetEntityDataPacket(fake.getId(), 
            fake.getEntityData().packDirty()));
        
        // Send potion effect updates - simplified approach
        for (ReplayFrame.PotionEffectData effectData : snap.potionEffects) {
            try {
                // For now, just log the effect - full packet implementation can be added later
                if (effectData.type.equals("INVISIBILITY")) {
                    // Handle invisibility specifically through entity metadata
                    broadcastPacket(new ClientboundSetEntityDataPacket(fake.getId(), 
                        fake.getEntityData().packDirty()));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Failed to send potion effect " + effectData.type + ": " + e.getMessage());
            }
        }
        
        // Remove effects that are no longer active - simplified approach
        if (prev != null) {
            for (ReplayFrame.PotionEffectData prevEffect : prev.potionEffects) {
                boolean stillActive = snap.potionEffects.stream()
                    .anyMatch(effect -> effect.type.equals(prevEffect.type));
                
                if (!stillActive) {
                    try {
                        if (prevEffect.type.equals("INVISIBILITY")) {
                            // Handle invisibility removal through entity metadata
                            broadcastPacket(new ClientboundSetEntityDataPacket(fake.getId(), 
                                fake.getEntityData().packDirty()));
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Replay] Failed to remove potion effect " + prevEffect.type + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private void broadcastPacket(net.minecraft.network.protocol.Packet<?> packet) {
        for (UUID vid : viewers) {
            Player v = plugin.getServer().getPlayer(vid);
            if (v != null) ((CraftPlayer) v).getHandle().connection.send(packet);
        }
    }
}
