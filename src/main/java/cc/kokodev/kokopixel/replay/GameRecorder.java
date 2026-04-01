package cc.kokodev.kokopixel.replay;

import cc.kokodev.kokopixel.minigames.GameInstanceImpl;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Attached to a live GameInstanceImpl. Samples the game state every tick
 * and accumulates ReplayFrames. Call stop() when the game ends to get the
 * finished ReplayRecording.
 */
public class GameRecorder {

    private final GameInstanceImpl game;
    private final JavaPlugin plugin;
    private final List<ReplayFrame> frames = new ArrayList<>();
    private final Set<UUID> participants = new LinkedHashSet<>();
    /** Skin textures captured once when each player is first seen. */
    private final Map<UUID, String> skinTextures = new LinkedHashMap<>();
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask task;
    private boolean stopped = false;

    // Block changes queued from event listeners this tick, flushed each frame
    private final List<ReplayFrame.BlockChange> pendingBlockChanges = new ArrayList<>();

    public GameRecorder(GameInstanceImpl game, JavaPlugin plugin) {
        this.game = game;
        this.plugin = plugin;
    }

    public void start() {
        // Sample every tick (1 tick = 50ms). For large games you could sample every 2 ticks.
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (stopped) return;

        ReplayFrame frame = new ReplayFrame();

        // Snapshot every player currently in the game
        for (var gp : game.getPlayers()) {
            Player p = gp.getPlayer();
            if (p == null || !p.isOnline()) continue;
            participants.add(p.getUniqueId());

            // Capture skin texture once per player using Paper's stable PlayerProfile API
            if (!skinTextures.containsKey(p.getUniqueId())) {
                try {
                    org.bukkit.profile.PlayerProfile pp = p.getPlayerProfile();
                    org.bukkit.profile.PlayerTextures textures = pp.getTextures();
                    if (textures.getSkin() != null) {
                        // Build a complete profile so the textures property is populated
                        org.bukkit.profile.PlayerProfile built =
                                plugin.getServer().createPlayerProfile(p.getUniqueId(), p.getName());
                        org.bukkit.profile.PlayerTextures bt = built.getTextures();
                        bt.setSkin(textures.getSkin(), textures.getSkinModel());
                        built.setTextures(bt);
                        // Extract the serialized textures value+signature via CraftPlayerProfile
                        Object craftProfile = built.getClass().getMethod("buildGameProfile").invoke(built);
                        // Find properties accessor — name varies by authlib version
                        Object propMap = null;
                        for (java.lang.reflect.Method m : craftProfile.getClass().getMethods()) {
                            if ((m.getName().equals("getProperties") || m.getName().equals("properties"))
                                    && m.getParameterCount() == 0) {
                                propMap = m.invoke(craftProfile);
                                break;
                            }
                        }
                        if (propMap == null) continue;
                        // PropertyMap.get("textures") returns a Collection
                        @SuppressWarnings("unchecked")
                        java.util.Collection<?> props = (java.util.Collection<?>)
                                propMap.getClass().getMethod("get", Object.class).invoke(propMap, "textures");
                        if (!props.isEmpty()) {
                            Object prop = props.iterator().next();
                            String value = (String) prop.getClass().getMethod("value").invoke(prop);
                            String sig;
                            try { sig = (String) prop.getClass().getMethod("signature").invoke(prop); }
                            catch (Exception ex) { sig = null; }
                            skinTextures.put(p.getUniqueId(),
                                    value + (sig != null ? "\n" + sig : ""));
                        }
                    }
                } catch (Exception ignored) {}
            }

            ItemStack held = p.getInventory().getItemInMainHand();
            String heldName = (held == null || held.getType() == Material.AIR)
                    ? "AIR" : held.getType().name();

            frame.players.put(p.getUniqueId(), new ReplayFrame.PlayerSnapshot(
                    p.getName(),
                    p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ(),
                    p.getLocation().getYaw(), p.getLocation().getPitch(),
                    heldName,
                    p.isSneaking(), p.isSprinting(), p.isOnGround()
            ));
        }

        // Flush block changes accumulated since last tick
        synchronized (pendingBlockChanges) {
            frame.blockChanges.addAll(pendingBlockChanges);
            pendingBlockChanges.clear();
        }

        frames.add(frame);
    }

    /** Called from GameListener when a block is broken/placed in this game's world. */
    public void recordBlockChange(int x, int y, int z, String material) {
        synchronized (pendingBlockChanges) {
            pendingBlockChanges.add(new ReplayFrame.BlockChange(x, y, z, material));
        }
    }

    /** Stop recording and return the finished ReplayRecording. */
    public ReplayRecording stop() {
        stopped = true;
        if (task != null) task.cancel();
        // Count frames that actually have player data
        long framesWithPlayers = frames.stream().filter(f -> !f.players.isEmpty()).count();
        plugin.getLogger().info("[Replay] Recording stopped: " + frames.size() + " total frames, "
                + framesWithPlayers + " with player data, "
                + participants.size() + " participants.");
        return new ReplayRecording(
                game.getGameId(),
                game.getGameType(),
                game.getGameType(),
                startedAt,
                Collections.unmodifiableSet(participants),
                Collections.unmodifiableList(new ArrayList<>(frames)),
                Collections.unmodifiableMap(new LinkedHashMap<>(skinTextures))
        );
    }
}
