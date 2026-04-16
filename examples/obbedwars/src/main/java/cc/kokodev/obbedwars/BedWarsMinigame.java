package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.minigames.Minigame;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class BedWarsMinigame extends Minigame {

    // teamName (lowercase) -> bed location (world=null, remapped at game start)
    private final Map<String, Location> bedLocations = new LinkedHashMap<>();
    // diamond generator locations (world=null, remapped at game start)
    private final List<Location> diamondGens = new ArrayList<>();
    // NPC shop locations (world=null, remapped at game start)
    private final List<Location> shopLocations = new ArrayList<>();

    private static final List<String> DEFAULT_TEAMS = List.of("red", "blue", "yellow", "lime");

    public BedWarsMinigame(JavaPlugin plugin) {
        super("obbedwars", "&cOneBlock &fBedWars", 2, 4, plugin, BedWarsGame.class);

        setSupportsTeams(true);
        setHandlesDeath(true);

        // Only add teams if none were loaded from config (prevents duplication on restart)
        if (getTeams().isEmpty()) {
            List<String> teams = plugin.getConfig().getStringList("teams");
            if (teams.isEmpty()) teams = DEFAULT_TEAMS;
            for (String team : teams) addTeam(team);
        }

        loadMapConfig(plugin);
    }

    private void loadMapConfig(JavaPlugin plugin) {
        File mapFile = new File(plugin.getDataFolder(), "map.yml");
        if (!mapFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mapFile);

        // Beds stored as beds.<team>.x/y/z — no world reference needed
        var bedsSection = cfg.getConfigurationSection("beds");
        if (bedsSection != null) {
            for (String team : bedsSection.getKeys(false)) {
                double x = cfg.getDouble("beds." + team + ".x");
                double y = cfg.getDouble("beds." + team + ".y");
                double z = cfg.getDouble("beds." + team + ".z");
                bedLocations.put(team.toLowerCase(), new Location(null, x, y, z));
            }
        }

        // Diamond gens stored as list of {x, y, z} maps
        var gensList = cfg.getMapList("diamond-gens");
        for (var map : gensList) {
            double x = ((Number) map.get("x")).doubleValue();
            double y = ((Number) map.get("y")).doubleValue();
            double z = ((Number) map.get("z")).doubleValue();
            diamondGens.add(new Location(null, x, y, z));
        }

        // Shop NPC locations
        var shopList = cfg.getMapList("shops");
        for (var map : shopList) {
            double x = ((Number) map.get("x")).doubleValue();
            double y = ((Number) map.get("y")).doubleValue();
            double z = ((Number) map.get("z")).doubleValue();
            shopLocations.add(new Location(null, x, y, z));
        }

        plugin.getLogger().info("[OBBedWars] Loaded " + bedLocations.size()
                + " beds, " + diamondGens.size() + " diamond gens, " + shopLocations.size() + " shops.");
    }

    public void saveMapConfig(JavaPlugin plugin) {
        File mapFile = new File(plugin.getDataFolder(), "map.yml");
        YamlConfiguration cfg = new YamlConfiguration();

        for (Map.Entry<String, Location> e : bedLocations.entrySet()) {
            cfg.set("beds." + e.getKey() + ".x", e.getValue().getX());
            cfg.set("beds." + e.getKey() + ".y", e.getValue().getY());
            cfg.set("beds." + e.getKey() + ".z", e.getValue().getZ());
        }

        List<Map<String, Object>> gens = new ArrayList<>();
        for (Location loc : diamondGens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", loc.getX()); m.put("y", loc.getY()); m.put("z", loc.getZ());
            gens.add(m);
        }
        cfg.set("diamond-gens", gens);

        List<Map<String, Object>> shops = new ArrayList<>();
        for (Location loc : shopLocations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", loc.getX()); m.put("y", loc.getY()); m.put("z", loc.getZ());
            shops.add(m);
        }
        cfg.set("shops", shops);

        try { cfg.save(mapFile); } catch (Exception ex) { ex.printStackTrace(); }
    }

    public void setBedLocation(String team, Location loc) {
        // Store only coordinates — world is irrelevant (remapped at game start)
        bedLocations.put(team.toLowerCase(), new Location(null, loc.getX(), loc.getY(), loc.getZ()));
    }

    public void addDiamondGen(Location loc) {
        diamondGens.add(new Location(null, loc.getX(), loc.getY(), loc.getZ()));
    }

    public void addShop(Location loc) {
        shopLocations.add(new Location(null, loc.getX(), loc.getY(), loc.getZ()));
    }

    public Map<String, Location> getBedLocations() { return bedLocations; }
    public List<Location> getDiamondGens() { return diamondGens; }
    public List<Location> getShopLocations() { return shopLocations; }
}
