package cc.kokodev.kokopixel.api.events;

import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public class GameStartEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final GameInstance game;
    private final List<UUID> players;

    public GameStartEvent(GameInstance game, List<UUID> players) {
        this.game = game;
        this.players = players;
    }

    public GameInstance getGame() { return game; }
    public List<UUID> getPlayers() { return players; }
    @NotNull @Override public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}