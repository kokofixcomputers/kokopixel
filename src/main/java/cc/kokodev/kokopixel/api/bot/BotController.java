package cc.kokodev.kokopixel.api.bot;

import cc.kokodev.kokopixel.api.game.GameInstance;

/**
 * Represents a single bot's AI logic for one game session.
 * Implement this in your game plugin to define how bots behave.
 * An instance is created per bot per game via {@link BotEngine#createController(BotHandle, GameInstance)}.
 *
 * <p>The {@link BotHandle} is the bot's "body" — use it to move, animate, and equip the bot.
 */
public interface BotController {

    /** Called once when the bot is spawned and ready to act. */
    void onStart(BotHandle bot, GameInstance game);

    /**
     * Called every N ticks (where N = {@link BotEngine#getTickInterval()}).
     * Put movement, targeting, and decision logic here.
     */
    void onTick(BotHandle bot, GameInstance game);

    /**
     * Called when the game signals that this bot died.
     * Return {@code true} to keep the bot alive (the controller is responsible for respawn logic).
     * Return {@code false} to remove the bot.
     */
    boolean onDeath(BotHandle bot, GameInstance game);

    /** Called when the game ends or the bot is explicitly removed. Clean up any state. */
    void onStop(BotHandle bot, GameInstance game);
}
