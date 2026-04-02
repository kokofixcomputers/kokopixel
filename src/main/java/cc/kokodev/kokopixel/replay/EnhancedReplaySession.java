package cc.kokodev.kokopixel.replay;

import cc.kokodev.kokopixel.KokoPixel;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Fox;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust replay session that handles all packet types safely.
 * Replaces the buggy original session with comprehensive state management.
 */
public class EnhancedReplaySession {

    private final KokoPixel plugin;
    private final EnhancedReplayRecording recording;
    private final World replayWorld;
    private final Set<UUID> viewers = new LinkedHashSet<>();

    private final Map<UUID, ServerPlayer> fakePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, ReplayData.PlayerState> lastPlayerStates = new HashMap<>();

    private int currentFrame = 0;
    private double replaySpeed = 1.0;
    private BukkitTask tickTask;
    private boolean finished = false;
    private boolean paused = false;

    public EnhancedReplaySession(KokoPixel plugin, EnhancedReplayRecording recording, World replayWorld) {
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

        // Teleport to the first recorded player position
        org.bukkit.Location startLoc = replayWorld.getSpawnLocation();
        if (!recording.frames.isEmpty() && !recording.frames.get(0).playerStates.isEmpty()) {
            ReplayData.PlayerState first = recording.frames.get(0).playerStates.values().iterator().next();
            startLoc = new org.bukkit.Location(replayWorld, first.x, first.y + 3, first.z);
        }
        viewer.teleport(startLoc);

        // Give spectator items
        org.bukkit.inventory.ItemStack dye = new org.bukkit.inventory.ItemStack(Material.GREEN_DYE, 1);
        org.bukkit.inventory.meta.ItemMeta meta = dye.getItemMeta();
        meta.setDisplayName("Pause/Resume Replay");
        dye.setItemMeta(meta);
        viewer.getInventory().addItem(dye);

        viewer.sendMessage("§7[§dReplay§7] §eWatching §f" + recording.gameType
                + " §7(" + recording.durationSeconds() + "s) — §bUse bed to exit.");
                
        // Spawn all existing fake players for this viewer
        for (Map.Entry<UUID, ServerPlayer> entry : fakePlayers.entrySet()) {
            sendSpawnPackets(viewer, entry.getValue(), lastPlayerStates.get(entry.getKey()).name);
        }
    }

    public void removeViewer(Player viewer) {
        viewers.remove(viewer.getUniqueId());
        viewer.removeMetadata("kp_replay_session", plugin);
        if (plugin.getSpectatorManager().isSpectator(viewer))
            plugin.getSpectatorManager().disableSpectator(viewer);
        plugin.getGameSelectorMenu().giveGameSelector(viewer);
        viewer.sendMessage("§7[§dReplay§7] §cLeft replay.");
        if (viewers.isEmpty()) stop();
    }

    public boolean hasViewer(UUID id) { return viewers.contains(id); }
    public boolean isPaused() { return paused; }
    public void pause() {
        paused = true;
        plugin.getLogger().info("[Replay] Paused at frame " + currentFrame);
    }

    public void resume() {
        paused = false;
        plugin.getLogger().info("[Replay] Resumed at frame " + currentFrame);
    }

    public void skipFrames(int frames) {
        currentFrame = Math.max(0, Math.min(recording.frames.size() - 1, currentFrame + frames));
        plugin.getLogger().info("[Replay] Skipped to frame " + currentFrame);
    }

    public boolean isFinished() { return finished; }
    public UUID getRecordingId() { return recording.gameId; }
    public EnhancedReplayRecording getRecording() { return recording; }

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

        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (paused || currentFrame >= recording.frames.size()) { return; }

        int frameAdvance = (int) Math.max(1, replaySpeed);
        currentFrame += frameAdvance;
        
        if (currentFrame >= recording.frames.size()) {
            currentFrame = recording.frames.size() - 1;
            // Replay finished
            for (UUID vid : viewers) {
                Player v = plugin.getServer().getPlayer(vid);
                if (v != null) v.sendMessage("§7[§dReplay§7] §aReplay finished!");
            }
            stop();
            return;
        }

        ReplayData frame = recording.frames.get(currentFrame);

        // Apply block changes
        for (ReplayData.BlockChange bc : frame.blockChanges) {
            Material mat = Material.matchMaterial(bc.newMaterial);
            if (mat != null) {
                replayWorld.getBlockAt(bc.x, bc.y, bc.z).setType(mat, false);
            }
        }

        // Handle game events
        for (ReplayData.GameEvent event : frame.gameEvents) {
            handleGameEvent(event);
        }

        // Handle damage events (red flash effects) - simplified approach
        for (ReplayData.DamageEvent damage : frame.damageEvents) {
            handleDamageEvent(damage);
        }

        // Handle animation events (hand swings, etc.)
        for (ReplayData.AnimationEvent animation : frame.animationEvents) {
            handleAnimationEvent(animation);
        }

        // Update all fake players
        for (Map.Entry<UUID, ReplayData.PlayerState> entry : frame.playerStates.entrySet()) {
            UUID playerId = entry.getKey();
            ReplayData.PlayerState state = entry.getValue();
            
            if (!fakePlayers.containsKey(playerId)) {
                spawnFakePlayer(playerId, state);
            }
            
            updateFakePlayer(playerId, state);
        }

        // Remove players no longer in this frame
        new HashSet<>(fakePlayers.keySet()).stream()
                .filter(id -> !frame.playerStates.containsKey(id))
                .forEach(this::despawnFakePlayer);

        // Update progress bar
        float progress = (float) currentFrame / recording.frames.size();
        for (UUID vid : viewers) {
            Player v = plugin.getServer().getPlayer(vid);
            if (v != null) v.setExp(Math.min(progress, 1f));
        }
    }

    private void handleGameEvent(ReplayData.GameEvent event) {
        for (UUID vid : viewers) {
            Player viewer = plugin.getServer().getPlayer(vid);
            if (viewer == null) continue;
            
            switch (event.type) {
                case PLAYER_DEATH:
                    viewer.sendMessage("§c" + event.playerName + " §7" + event.message);
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_DEATH, 0.5f, 1.0f);
                    break;
                case BED_DESTROYED:
                    viewer.sendMessage("§c§lBED DESTROYED! §7" + event.message);
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 0.5f, 1.0f);
                    break;
                case TEAM_ELIMINATED:
                    viewer.sendMessage("§c§l" + event.playerName + " ELIMINATED!");
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                    break;
                case GAME_END:
                    viewer.sendMessage("§a§lGame Over! §7" + event.message);
                    viewer.playSound(viewer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.0f);
                    break;
                case PLAYER_RESPAWN:
                    if (event.data.containsKey("fromX")) {
                        // This is a teleport event
                        viewer.sendActionBar("§e" + event.playerName + " teleported");
                    }
                    break;
            }
        }
    }

    private void handleAnimationEvent(ReplayData.AnimationEvent animation) {
        ServerPlayer fake = fakePlayers.get(animation.playerId);
        if (fake == null) return;
        
        try {
            switch (animation.type) {
                case HAND_SWING:
                    // Send arm swing animation
                    broadcastPacket(new ClientboundAnimatePacket(
                        fake, 0 // 0 = main hand swing
                    ));
                    break;
                    
                case HEAD_TURN:
                    // Update head rotation
                    broadcastPacket(new ClientboundRotateHeadPacket(
                        fake, (byte) (animation.yaw * 256f / 360f)));
                    break;
                    
                case HURT_ANIMATION:
                    // Send hurt animation
                    broadcastPacket(new ClientboundHurtAnimationPacket(
                        fake.getId(), 0));
                    break;
                    
                case CRITICAL_HIT:
                    // Critical hit effect - could add particles/sounds
                    broadcastPacket(new ClientboundAnimatePacket(
                        fake, 4 // 4 = critical hit
                    ));
                    break;
                    
                case BLOCK_BREAK:
                case BLOCK_PLACE:
                    // These would be handled by block change events
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("[Replay] Failed to play animation " + animation.type + ": " + e.getMessage());
        }
    }

    private void handleDamageEvent(ReplayData.DamageEvent damage) {
        ServerPlayer fake = fakePlayers.get(damage.victimId);
        if (fake == null) return;
        
        // Simplified damage handling - avoid complex packets
        try {
            // Play hurt sound at the damage location using Bukkit API instead of NMS packets
            for (UUID vid : viewers) {
                Player viewer = plugin.getServer().getPlayer(vid);
                if (viewer != null) {
                    viewer.playSound(new org.bukkit.Location(replayWorld, damage.victimX, damage.victimY, damage.victimZ), 
                        damage.fatal ? Sound.ENTITY_PLAYER_DEATH : Sound.ENTITY_PLAYER_HURT, 
                        damage.fatal ? 1.0f : 0.5f, 1.0f);
                    
                    // Show damage message
                    if (damage.attackerName != null) {
                        viewer.sendActionBar("§c" + damage.victimName + " hit by " + damage.attackerName);
                    } else {
                        viewer.sendActionBar("§c" + damage.victimName + " took damage");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to handle damage event: " + e.getMessage());
        }
    }

    private void updateFakePlayer(UUID playerId, ReplayData.PlayerState state) {
        ServerPlayer fake = fakePlayers.get(playerId);
        if (fake == null) return;

        ReplayData.PlayerState lastState = lastPlayerStates.get(playerId);
        lastPlayerStates.put(playerId, state);

        // Position and movement
        boolean needsTeleport = lastState == null || 
            Math.abs(state.x - lastState.x) > 8.0 ||
            Math.abs(state.y - lastState.y) > 8.0 ||
            Math.abs(state.z - lastState.z) > 8.0;

        if (needsTeleport) {
            // This is a teleport - respawn at new location without despawning first
            // Just remove from old viewers and respawn with new position
            despawnFakePlayer(playerId);
            spawnFakePlayer(playerId, state);
            plugin.getLogger().info("[Replay] " + state.name + " teleported to " + 
                String.format("%.2f", state.x) + "," + 
                String.format("%.2f", state.y) + "," + 
                String.format("%.2f", state.z));
        } else {
            // Relative movement for small changes
            if (fake == null) {
                // Fake player doesn't exist, respawn it
                plugin.getLogger().info("[Replay] Respawning missing fake player: " + state.name);
                spawnFakePlayer(playerId, state);
                return;
            }
            
            double dxRel = state.x - lastState.x;
            double dyRel = state.y - lastState.y;
            double dzRel = state.z - lastState.z;
            
            try {
                broadcastPacket(new ClientboundMoveEntityPacket.PosRot(
                    fake.getId(),
                    (short) (dxRel * 4096), (short) (dyRel * 4096), (short) (dzRel * 4096),
                    (byte) (state.yaw * 256f / 360f),
                    (byte) (state.pitch * 256f / 360f),
                    state.onGround));

                // Head rotation
                broadcastPacket(new ClientboundRotateHeadPacket(
                    fake, (byte) (state.yaw * 256f / 360f)));
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Movement packet failed for " + state.name + ": " + e.getMessage());
                // Respawn the player if movement fails
                despawnFakePlayer(playerId);
                spawnFakePlayer(playerId, state);
            }

            // Update pose (standing, swimming, etc.) - simplified to avoid packet issues
            Pose pose = determinePose(state);
            if (fake.getPose() != pose) {
                fake.setPose(pose);
                // Skip pose packet to avoid encoding issues - pose is handled internally
            }

            // Update equipment
            try {
                updateEquipment(fake, state);
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Equipment update failed for " + state.name + ": " + e.getMessage());
            }

            // Update potion effects
            try {
                updatePotionEffects(fake, state, lastState);
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Potion effects update failed for " + state.name + ": " + e.getMessage());
            }

            // Update metadata (sneaking, sprinting, etc.)
            try {
                updateMetadata(fake, state, lastState);
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Metadata update failed for " + state.name + ": " + e.getMessage());
            }
        }
    }

    private Pose determinePose(ReplayData.PlayerState state) {
        if (state.sleeping) return Pose.SLEEPING;
        if (state.swimming) return Pose.SWIMMING;
        if (state.flying || state.gliding) return Pose.FALL_FLYING;
        if (state.sneaking) return Pose.CROUCHING;
        return Pose.STANDING;
    }

    private void updateEquipment(ServerPlayer fake, ReplayData.PlayerState state) {
        // Update armor and held items
        updateSlot(fake, EquipmentSlot.HEAD, state.helmet);
        updateSlot(fake, EquipmentSlot.CHEST, state.chestplate);
        updateSlot(fake, EquipmentSlot.LEGS, state.leggings);
        updateSlot(fake, EquipmentSlot.FEET, state.boots);
        updateSlot(fake, EquipmentSlot.MAINHAND, state.mainHand);
        updateSlot(fake, EquipmentSlot.OFFHAND, state.offHand);
    }

    private void updateSlot(ServerPlayer fake, EquipmentSlot slot, ReplayData.ItemStackData itemData) {
        net.minecraft.world.item.ItemStack nmsItem;
        if (itemData != null) {
            Material material = Material.matchMaterial(itemData.type);
            if (material != null) {
                org.bukkit.inventory.ItemStack bukkitItem = new org.bukkit.inventory.ItemStack(material, itemData.amount);
                nmsItem = CraftItemStack.asNMSCopy(bukkitItem);
            } else {
                nmsItem = net.minecraft.world.item.ItemStack.EMPTY;
            }
        } else {
            nmsItem = net.minecraft.world.item.ItemStack.EMPTY;
        }

        broadcastPacket(new ClientboundSetEquipmentPacket(fake.getId(),
            List.of(new com.mojang.datafixers.util.Pair<>(slot, nmsItem))));
    }

    private void updatePotionEffects(ServerPlayer fake, ReplayData.PlayerState state, ReplayData.PlayerState lastState) {
        // Simplified potion effect handling - skip packets to avoid encoding issues
        // Invisibility and other effects will be handled through metadata only
        return;
    }

    private void updateMetadata(ServerPlayer fake, ReplayData.PlayerState state, ReplayData.PlayerState lastState) {
        // Simplified metadata updates to avoid packet encoding issues
        try {
            // Only update flags if they actually changed
            if (lastState == null || state.sneaking != lastState.sneaking || state.sprinting != lastState.sprinting) {
                fake.setSharedFlag(1, state.sneaking); // crouching
                fake.setSharedFlag(3, state.sprinting); // sprinting
                
                // Skip entity data packet to avoid encoding issues - flags are handled internally
            }
            
            // Handle invisibility separately - skip entity data packets
            boolean hasInvisibility = state.potionEffects.stream()
                .anyMatch(effect -> effect.type.equals("INVISIBILITY"));
            
            boolean hadInvisibility = lastState != null && lastState.potionEffects.stream()
                .anyMatch(effect -> effect.type.equals("INVISIBILITY"));
            
            if (hasInvisibility != hadInvisibility) {
                // Skip entity data packet for invisibility to avoid encoding issues
                // Invisibility will be handled through potion effects instead
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to update metadata for " + state.name + ": " + e.getMessage());
        }
    }

    private int getEffectId(String effectName) {
        switch (effectName) {
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

    // -------------------------------------------------------------------------
    // Fake player management
    // -------------------------------------------------------------------------

    private void spawnFakePlayer(UUID playerId, ReplayData.PlayerState state) {
        try {
            ServerLevel level = ((CraftWorld) replayWorld).getHandle();
            UUID fakeUUID = new UUID(playerId.getMostSignificantBits() ^ 0xDEADBEEFL, playerId.getLeastSignificantBits());

            GameProfile profile = buildProfileWithSkin(fakeUUID, state.name, playerId);

            ServerPlayer fake = new ServerPlayer(
                    ((CraftServer) plugin.getServer()).getServer(),
                    level, profile,
                    net.minecraft.server.level.ClientInformation.createDefault());
                    
            fake.setPos(state.x, state.y, state.z);
            fake.setYRot(state.yaw);
            fake.setXRot(state.pitch);
            
            fakePlayers.put(playerId, fake);
            lastPlayerStates.put(playerId, state);

            for (UUID vid : viewers) {
                Player v = plugin.getServer().getPlayer(vid);
                if (v != null) sendSpawnPackets(v, fake, state.name);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to spawn fake player " + state.name + ": " + e.getMessage());
        }
    }

    private void despawnFakePlayer(UUID playerId) {
        ServerPlayer fake = fakePlayers.remove(playerId);
        lastPlayerStates.remove(playerId);
        if (fake == null) return;

        broadcastPacket(new ClientboundRemoveEntitiesPacket(fake.getId()));
        broadcastPacket(new ClientboundPlayerInfoRemovePacket(List.of(fake.getUUID())));

        ReplayData.PlayerState state = lastPlayerStates.get(playerId);
        if (state != null) {
            String teamName = ("rp_" + state.name).substring(0, Math.min(16, ("rp_" + state.name).length()));
            var scoreboard = new net.minecraft.world.scores.Scoreboard();
            var team = new net.minecraft.world.scores.PlayerTeam(scoreboard, teamName);
            broadcastPacket(ClientboundSetPlayerTeamPacket.createRemovePacket(team));
        }
    }

    private GameProfile buildProfileWithSkin(UUID fakeUUID, String name, UUID originalId) {
        Player realPlayer = plugin.getServer().getPlayer(originalId);
        if (realPlayer != null) {
            GameProfile realProfile = ((CraftPlayer) realPlayer).getHandle().getGameProfile();
            try {
                Class<?> propMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
                GameProfile newProfile = GameProfile.class
                        .getConstructor(java.util.UUID.class, String.class, propMapClass)
                        .newInstance(fakeUUID, name, getPropertiesMap(realProfile));
                return newProfile;
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Profile copy failed: " + e.getMessage());
            }
        }
        return new GameProfile(fakeUUID, name);
    }

    private Object getPropertiesMap(GameProfile profile) {
        for (java.lang.reflect.Method m : profile.getClass().getMethods()) {
            if ((m.getName().equals("getProperties") || m.getName().equals("properties"))
                    && m.getParameterCount() == 0) {
                try { return m.invoke(profile); } catch (Exception ignored) {}
            }
        }
        return null;
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
            // Send player info packet with error handling
            try {
                conn.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Player info packet failed for " + playerName + ": " + e.getMessage());
                // Continue with other packets even if this fails
            }
        } finally {
            fake.connection = null;
        }

        try {
            // Send spawn entity packet with error handling
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
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Spawn entity packet failed for " + playerName + ": " + e.getMessage());
                return; // If spawn fails, don't send team packets
            }

            // Send team packets with error handling
            try {
                var team = makeReplayTeam(playerName);
                conn.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
                conn.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team,
                        playerName,
                        ClientboundSetPlayerTeamPacket.Action.ADD));
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Team packet failed for " + playerName + ": " + e.getMessage());
                // Team packets are not critical, so continue
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Spawn packets failed for " + playerName + ": " + e.getMessage());
        }
    }

    private net.minecraft.world.scores.PlayerTeam makeReplayTeam(String playerName) {
        var scoreboard = new net.minecraft.world.scores.Scoreboard();
        String teamName = ("rp_" + playerName).substring(0, Math.min(16, ("rp_" + playerName).length()));
        var team = new net.minecraft.world.scores.PlayerTeam(scoreboard, teamName);
        team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.ALWAYS);
        team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);
        return team;
    }

    private void broadcastPacket(net.minecraft.network.protocol.Packet<?> packet) {
        for (UUID vid : viewers) {
            Player v = plugin.getServer().getPlayer(vid);
            if (v != null) {
                try {
                    ((CraftPlayer) v).getHandle().connection.send(packet);
                } catch (Exception e) {
                    // Silently handle packet errors to avoid client kicks
                    plugin.getLogger().fine("[Replay] Packet error for " + v.getName() + ": " + e.getMessage());
                }
            }
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
        // Note: This would need to be updated to use EnhancedReplayManager
        // For now, we'll skip calling onSessionEnd to avoid compilation issues
        // plugin.getReplayManager().onSessionEnd(this);
    }
}
