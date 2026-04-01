package cc.kokodev.kokopixel.minigames;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public abstract class Minigame {
    private final String name;
    private final String displayName;
    private final int minPlayers;
    private final int maxPlayers;
    private final JavaPlugin plugin;
    private final Class<? extends GameInstanceImpl> gameClass;
    private World templateWorld;
    private String templateWorldName;
    private final List<Location> spawnPoints = new ArrayList<>();
    private final Map<String, List<Location>> teamSpawnPoints = new HashMap<>();
    private final List<String> teams = new ArrayList<>();
    private boolean supportsTeams = false;
    private List<GameInstanceImpl> activeGames = new ArrayList<>();
    private final File configFile;

    public Minigame(String name, String displayName, int minPlayers, int maxPlayers, JavaPlugin plugin, Class<? extends GameInstanceImpl> gameClass) {
        this.name = name;
        this.displayName = displayName;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.plugin = plugin;
        this.gameClass = gameClass;
        this.configFile = new File(plugin.getDataFolder() + "/minigames", name + ".yml");
        loadFromConfig();
    }

    public String getName() { return name; }
    public String getDisplayName() { return ChatColor.translateAlternateColorCodes('&', displayName); }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public JavaPlugin getPlugin() { return plugin; }
    public Class<? extends GameInstanceImpl> getGameClass() { return gameClass; }
    public World getTemplateWorld() { if (templateWorld == null && templateWorldName != null) templateWorld = Bukkit.getWorld(templateWorldName); return templateWorld; }
    public void setTemplateWorld(World world) { this.templateWorld = world; this.templateWorldName = world.getName(); saveToConfig(); }
    public List<Location> getSpawnPoints() { return spawnPoints; }
    public void addSpawnPoint(Location loc) { spawnPoints.add(loc.clone()); saveToConfig(); }
    public void clearSpawnPoints() { spawnPoints.clear(); saveToConfig(); }
    public boolean supportsTeams() { return supportsTeams; }
    public void setSupportsTeams(boolean b) { this.supportsTeams = b; saveToConfig(); }
    public List<String> getTeams() { return teams; }
    public void addTeam(String team) { teams.add(team); teamSpawnPoints.putIfAbsent(team, new ArrayList<>()); saveToConfig(); }
    public void addTeamSpawnPoint(String team, Location loc) { teamSpawnPoints.computeIfAbsent(team, k -> new ArrayList<>()).add(loc.clone()); saveToConfig(); }
    public List<Location> getTeamSpawnPoints(String team) { return teamSpawnPoints.getOrDefault(team, new ArrayList<>()); }
    public void clearTeamSpawnPoints(String team) { teamSpawnPoints.getOrDefault(team, new ArrayList<>()).clear(); saveToConfig(); }
    public List<GameInstanceImpl> getActiveGames() { return activeGames; }
    public void addGame(GameInstanceImpl game) { activeGames.add(game); }
    public void removeGame(GameInstanceImpl game) { activeGames.remove(game); }

    public GameInstanceImpl createInstance(World world) {
        try {
            GameInstanceImpl game = gameClass.getConstructor(Minigame.class, World.class, JavaPlugin.class).newInstance(this, world, plugin);
            spawnPoints.forEach(s -> game.addSpawnPoint(s.clone()));
            teamSpawnPoints.forEach((t, list) -> list.forEach(s -> game.addTeamSpawnPoint(t, s.clone())));
            return game;
        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    private void loadFromConfig() {
        if (!configFile.exists()) { saveToConfig(); return; }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        this.templateWorldName = cfg.getString("template-world");
        if (templateWorldName != null) this.templateWorld = Bukkit.getWorld(templateWorldName);
        spawnPoints.clear();
        List<Map<String, Object>> spawnList = (List<Map<String, Object>>) cfg.getList("spawn-points");
        if (spawnList != null) {
            for (Map<String, Object> map : spawnList) {
                World w = Bukkit.getWorld((String) map.get("world"));
                if (w != null) spawnPoints.add(new Location(w, (double) map.get("x"), (double) map.get("y"), (double) map.get("z"), ((Double) map.get("yaw")).floatValue(), ((Double) map.get("pitch")).floatValue()));
            }
        }
        this.supportsTeams = cfg.getBoolean("supports-teams", false);
        teams.clear(); teams.addAll(cfg.getStringList("teams"));
        teamSpawnPoints.clear();
        for (String t : teams) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) cfg.getList("team-spawn-points." + t);
            if (list != null) {
                List<Location> locs = new ArrayList<>();
                for (Map<String, Object> map : list) {
                    World w = Bukkit.getWorld((String) map.get("world"));
                    if (w != null) locs.add(new Location(w, (double) map.get("x"), (double) map.get("y"), (double) map.get("z"), ((Double) map.get("yaw")).floatValue(), ((Double) map.get("pitch")).floatValue()));
                }
                teamSpawnPoints.put(t, locs);
            }
        }
    }

    public void saveToConfig() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("name", name);
            cfg.set("display-name", displayName);
            cfg.set("min-players", minPlayers);
            cfg.set("max-players", maxPlayers);
            if (templateWorld != null) cfg.set("template-world", templateWorld.getName());
            else if (templateWorldName != null) cfg.set("template-world", templateWorldName);
            List<Map<String, Object>> spawnList = new ArrayList<>();
            for (Location l : spawnPoints) {
                Map<String, Object> m = new HashMap<>();
                m.put("world", l.getWorld().getName()); m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ()); m.put("yaw", (double) l.getYaw()); m.put("pitch", (double) l.getPitch());
                spawnList.add(m);
            }
            cfg.set("spawn-points", spawnList);
            cfg.set("supports-teams", supportsTeams);
            cfg.set("teams", teams);
            for (Map.Entry<String, List<Location>> e : teamSpawnPoints.entrySet()) {
                List<Map<String, Object>> teamList = new ArrayList<>();
                for (Location l : e.getValue()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("world", l.getWorld().getName()); m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ()); m.put("yaw", (double) l.getYaw()); m.put("pitch", (double) l.getPitch());
                    teamList.add(m);
                }
                cfg.set("team-spawn-points." + e.getKey(), teamList);
            }
            configFile.getParentFile().mkdirs();
            cfg.save(configFile);
        } catch (IOException e) { e.printStackTrace(); }
    }
}