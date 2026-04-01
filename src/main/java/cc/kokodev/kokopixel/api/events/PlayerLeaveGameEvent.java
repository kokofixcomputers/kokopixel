package cc.kokodev.kokopixel.api.events;

import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

public class PlayerLeaveGameEvent extends PlayerEvent {
    private static final HandlerList handlers = new HandlerList();
    private final GameInstance game;

    public PlayerLeaveGameEvent(Player player, GameInstance game) {
        super(player);
        this.game = game;
    }

    public GameInstance getGame() { return game; }
    @NotNull @Override public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}