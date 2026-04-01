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
        return new ReplayRecording(
                game.getGameId(),
                game.getGameType(),
                game.getGameType(), // minigameName == gameType for template lookup
                startedAt,
                Collections.unmodifiableSet(participants),
                Collections.unmodifiableList(new ArrayList<>(frames))
        );
    }
}
