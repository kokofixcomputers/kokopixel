package cc.kokodev.kokopixel.api.events;

import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public class GameEndEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final GameInstance game;
    private final List<UUID> winners;

    public GameEndEvent(GameInstance game, List<UUID> winners) {
        this.game = game;
        this.winners = winners;
    }

    public GameInstance getGame() { return game; }
    public List<UUID> getWinners() { return winners; }
    @NotNull @Override public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}