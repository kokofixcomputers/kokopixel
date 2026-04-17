package cc.kokodev.kokopixel.api.bot;

import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Describes a bot engine that can be registered with KokoPixel.
 * Extend {@link cc.kokodev.kokopixel.bots.BotEngineImpl} rather than implementing this directly.
 *
 * <p>Registration (in your plugin's onEnable, 1 tick after KokoPixel loads):
 * <pre>
 *   KokoPixel.getInstance().getBotManager().registerEngine(new MyBotEngine(this));
 * </pre>
 */
public interface BotEngine {

    /** Unique identifier for this engine, e.g. "obbedwars_basic". */
    String getId();

    /** Human-readable name shown in admin UIs. */
    String getDisplayName();

    /** The plugin that owns this engine. */
    JavaPlugin getPlugin();

    /**
     * Factory: create a {@link BotController} for the given bot handle joining the given game.
     * Called by {@link cc.kokodev.kokopixel.bots.BotManager} after the NMS fake player is spawned.
     */
    BotController createController(BotHandle bot, GameInstance game);

    /**
     * How many ticks between each {@link BotController#onTick} call.
     * Default is 1 (every tick). Override to reduce CPU cost.
     */
    default int getTickInterval() { return 1; }

    /**
     * The set of minigame names this engine supports, e.g. {@code ["skywars", "bedwars"]}.
     * Return an empty set to indicate the engine works with any game.
     * Used by the party bot system to warn or reject incompatible games at queue time.
     */
    default java.util.Set<String> getSupportedGames() { return java.util.Collections.emptySet(); }

    /**
     * Returns true if this engine supports the given game (case-insensitive).
     * Always returns true when {@link #getSupportedGames()} is empty.
     */
    default boolean isGameSupported(String gameId) {
        java.util.Set<String> supported = getSupportedGames();
        if (supported.isEmpty()) return true;
        return supported.stream().anyMatch(s -> s.equalsIgnoreCase(gameId));
    }
}
