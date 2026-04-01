package cc.kokodev.kokopixel.minigames;

import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameTeam;
import org.bukkit.ChatColor;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameTeamImpl implements GameTeam {
    private final String name;
    private final ChatColor color;
    private final List<GamePlayer> members = new CopyOnWriteArrayList<>();
    private Location spawnPoint;
    private int score = 0;
    private boolean eliminated = false;

    public GameTeamImpl(String name) { this(name, ChatColor.valueOf(name.toUpperCase())); }
    public GameTeamImpl(String name, ChatColor color) { this.name = name; this.color = color; }
    @Override public String getName() { return name; }
    @Override public ChatColor getColor() { return color; }
    @Override public List<GamePlayer> getMembers() { return new ArrayList<>(members); }
    @Override public void addMember(GamePlayer p) { members.add(p); }
    @Override public void removeMember(GamePlayer p) { members.remove(p); }
    @Override public boolean isMember(UUID id) { return members.stream().anyMatch(p -> p.getUniqueId().equals(id)); }
    @Override public int getMemberCount() { return members.size(); }
    @Override public Location getSpawnPoint() { return spawnPoint; }
    @Override public void setSpawnPoint(Location l) { this.spawnPoint = l.clone(); }
    @Override public int getScore() { return score; }
    @Override public void setScore(int s) { this.score = s; }
    @Override public void addScore(int a) { this.score += a; }
    @Override public boolean isEliminated() { return eliminated; }
    @Override public void setEliminated(boolean e) { this.eliminated = e; }
}