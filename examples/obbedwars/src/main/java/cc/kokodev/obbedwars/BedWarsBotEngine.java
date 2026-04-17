package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.api.bot.BotController;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.bots.BotEngineImpl;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BedWars bot engine — produces AI controllers that can:
 * - Rush and break enemy beds
 * - Bridge over gaps
 * - Fight players (melee + fireball)
 * - Use the Fireball custom item
 */
public class BedWarsBotEngine extends BotEngineImpl {

    public BedWarsBotEngine(JavaPlugin plugin) {
        super("obbedwars_bot", "&cBedWars Bot", plugin);
        addSupportedGame("obbedwars");
    }

    @Override
    public BotController createController(BotHandle bot, GameInstance game) {
        return new BedWarsBotController(getPlugin());
    }

    @Override
    public int getTickInterval() { return 2; } // run AI every 2 ticks
}
