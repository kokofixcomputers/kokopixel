package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Broadcasts bot "thought" messages to all players in the game world.
 * Messages are rate-limited per bot so they don't spam every tick.
 */
public class BotDebugger {

    private final String botName;
    private final GameInstance game;

    // Last message sent per category — suppress repeats
    private String lastGoal = "";
    private String lastThought = "";
    private long lastThoughtMs = 0;
    private static final long THOUGHT_COOLDOWN_MS = 2000; // 2 s between same thought

    public BotDebugger(String botName, GameInstance game) {
        this.botName = botName;
        this.game = game;
    }

    /** Announce a new goal (always shown, deduplicated). */
    public void goal(String message) {
        if (message.equals(lastGoal)) return;
        lastGoal = message;
        broadcast(ChatColor.AQUA + "[" + botName + "] " + ChatColor.YELLOW + "New goal: " + ChatColor.WHITE + message);
    }

    /** Announce a thought/decision (rate-limited, deduplicated). */
    public void think(String message) {
        long now = System.currentTimeMillis();
        if (message.equals(lastThought) && now - lastThoughtMs < THOUGHT_COOLDOWN_MS) return;
        lastThought = message;
        lastThoughtMs = now;
        broadcast(ChatColor.GRAY + "[" + botName + "] " + ChatColor.WHITE + message);
    }

    /** Announce an event (always shown). */
    public void event(String message) {
        broadcast(ChatColor.GREEN + "[" + botName + "] " + ChatColor.WHITE + message);
    }

    /** Announce a warning/problem. */
    public void warn(String message) {
        broadcast(ChatColor.RED + "[" + botName + "] " + ChatColor.WHITE + message);
    }

    private void broadcast(String msg) {
        for (cc.kokodev.kokopixel.api.game.GamePlayer gp : game.getPlayers()) {
            // Skip bots themselves
            if (cc.kokodev.kokopixel.KokoPixel.getInstance()
                    .getBotManager().isBot(gp.getUniqueId())) continue;
            gp.getPlayer().sendMessage(msg);
        }
    }
}
