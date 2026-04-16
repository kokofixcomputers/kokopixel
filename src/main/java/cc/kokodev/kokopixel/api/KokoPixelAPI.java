package cc.kokodev.kokopixel.api;

import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.bots.BotManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface KokoPixelAPI {
    static KokoPixelAPI get() { return KokoPixelAPIProvider.get(); }
    
    Optional<GameInstance> getGame(Player player);
    Optional<GameInstance> getGame(UUID playerId);
    boolean isInGame(Player player);
    void joinQueue(Player player, String gameType);
    void leaveQueue(Player player);
    boolean isInQueue(Player player);
    void enableSpectator(Player player);
    void enableSpectator(Player player, boolean canReturnToLobby);
    void disableSpectator(Player player);
    boolean isSpectating(Player player);
    void teleportToPlayer(Player spectator, Player target);
    String getCurrentServerId();
    void setGameSelectorBlock(Location location, String gameType);
    void removeGameSelectorBlock(Location location);
    void sendGameMessage(UUID gameId, String message);
    Optional<Integer> getPlayerStat(UUID playerId, UUID gameId, String stat);

    /** Access the bot engine registry and lifecycle manager. */
    BotManager getBotManager();
}
