package cc.kokodev.kokopixel.bots;

import cc.kokodev.kokopixel.api.bot.BotController;
import cc.kokodev.kokopixel.api.bot.BotEngine;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Convenience base class for {@link BotEngine}.
 * Extend this in your plugin to register a bot engine.
 *
 * <p>Minimal example:
 * <pre>
 * public class MyBotEngine extends BotEngineImpl {
 *     public MyBotEngine(JavaPlugin plugin) {
 *         super("mygame_basic", "&7Basic Bot", plugin);
 *     }
 *
 *     {@literal @}Override
 *     public BotController createController(BotHandle bot, GameInstance game) {
 *         return new MyBotController();
 *     }
 * }
 * </pre>
 */
public abstract class BotEngineImpl implements BotEngine {

    private final String id;
    private final String displayName;
    private final JavaPlugin plugin;
    private final java.util.Set<String> supportedGames = new java.util.HashSet<>();

    public BotEngineImpl(String id, String displayName, JavaPlugin plugin) {
        this.id = id.toLowerCase();
        this.displayName = displayName;
        this.plugin = plugin;
    }

    @Override public String getId() { return id; }
    @Override public String getDisplayName() { return org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName); }
    @Override public JavaPlugin getPlugin() { return plugin; }

    /** Restrict this engine to specific games. Call in your constructor, e.g. {@code addSupportedGame("bedwars")}. */
    protected void addSupportedGame(String gameId) { supportedGames.add(gameId.toLowerCase()); }

    @Override public java.util.Set<String> getSupportedGames() { return java.util.Collections.unmodifiableSet(supportedGames); }

    @Override
    public abstract BotController createController(BotHandle bot, GameInstance game);
}
