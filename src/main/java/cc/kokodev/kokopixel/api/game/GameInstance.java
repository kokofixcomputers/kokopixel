package cc.kokodev.kokopixel.api.game;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GameInstance {
    UUID getGameId();
    String getGameType();
    GameState getState();
    World getWorld();
    List<GamePlayer> getPlayers();
    Optional<GamePlayer> getPlayer(UUID playerId);
    List<GameTeam> getTeams();
    Optional<GameTeam> getTeam(String name);
    Optional<GameTeam> getPlayerTeam(UUID playerId);
    void broadcast(String message);
    void broadcastTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut);
    void setStat(UUID playerId, String stat, int value);
    void incrementStat(UUID playerId, String stat, int amount);
    int getStat(UUID playerId, String stat);
    Map<String, Integer> getStats(UUID playerId);
    void teleport(Player player, Location location);
    void teleportToSpawn(Player player);
    void teleportToTeamSpawn(Player player, String teamName);
    void setSpawnPoint(Location location);
    void setTeamSpawnPoint(String teamName, Location location);
    void start();
    void end(List<UUID> winners);
    void end();
    boolean isPrivate();
    void setPrivate(boolean isPrivate);
    void runTaskLater(Runnable task, long delay);
    void runTaskTimer(Runnable task, long delay, long period);
    JavaPlugin getPlugin();
    Map<String, Object> getData();
}