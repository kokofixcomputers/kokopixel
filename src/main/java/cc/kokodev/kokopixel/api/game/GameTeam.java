package cc.kokodev.kokopixel.api.game;

import org.bukkit.ChatColor;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

public interface GameTeam {
    String getName();
    ChatColor getColor();
    List<GamePlayer> getMembers();
    void addMember(GamePlayer player);
    void removeMember(GamePlayer player);
    boolean isMember(UUID playerId);
    int getMemberCount();
    Location getSpawnPoint();
    void setSpawnPoint(Location location);
    int getScore();
    void setScore(int score);
    void addScore(int amount);
    boolean isEliminated();
    void setEliminated(boolean eliminated);
}