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

    public GameTeamImpl(String name) { this(name, resolveColor(name)); }
    public GameTeamImpl(String name, ChatColor color) { this.name = name; this.color = color; }

    /**
     * Resolves a team name to a ChatColor.
     * Tries exact match first, then common aliases (lime→GREEN, purple→LIGHT_PURPLE, etc.).
     * Falls back to WHITE so a bad name never crashes the game.
     */
    private static ChatColor resolveColor(String name) {
        // Direct match
        try { return ChatColor.valueOf(name.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        // Common aliases minigame devs might use
        return switch (name.toLowerCase()) {
            case "lime"         -> ChatColor.GREEN;
            case "purple"       -> ChatColor.LIGHT_PURPLE;
            case "pink"         -> ChatColor.LIGHT_PURPLE;
            case "cyan"         -> ChatColor.AQUA;
            case "orange"       -> ChatColor.GOLD;
            case "gray", "grey" -> ChatColor.GRAY;
            case "darkgray", "dark_gray", "darkgrey" -> ChatColor.DARK_GRAY;
            case "lightblue", "light_blue" -> ChatColor.AQUA;
            default             -> ChatColor.WHITE;
        };
    }
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