package cc.kokodev.kokopixel.ranks;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RankManager {
    private final KokoPixel plugin;
    private final Map<String, Rank> ranks = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> playerRanks = new ConcurrentHashMap<>();
    private final File ranksFolder, playerDataFolder;

    public RankManager(KokoPixel plugin) {
        this.plugin = plugin;
        this.ranksFolder = new File(plugin.getDataFolder(), "ranks");
        this.playerDataFolder = new File(plugin.getDataFolder(), "playerdata");
        ranksFolder.mkdirs(); playerDataFolder.mkdirs();
        try { org.bukkit.configuration.serialization.ConfigurationSerialization.registerClass(Rank.class); } catch (Exception e) {}
        loadDefaultRanks(); loadRanks();
    }

    private void loadDefaultRanks() {
        createIfNotExists("owner", new Rank("owner", "&c[OWNER]", "Owner", ChatColor.RED, 100, false));
        createIfNotExists("admin", new Rank("admin", "&c[ADMIN]", "Admin", ChatColor.RED, 90, false));
        createIfNotExists("mod", new Rank("mod", "&9[MOD]", "Mod", ChatColor.BLUE, 80, false));
        createIfNotExists("helper", new Rank("helper", "&a[HELPER]", "Helper", ChatColor.GREEN, 70, false));
        createIfNotExists("builder", new Rank("builder", "&2[BUILDER]", "Builder", ChatColor.DARK_GREEN, 60, false));
        createIfNotExists("vip", new Rank("vip", "&6[VIP]", "VIP", ChatColor.GOLD, 50, false));
        createIfNotExists("mvp", new Rank("mvp", "&b[MVP]", "MVP", ChatColor.AQUA, 40, false));
        createIfNotExists("default", new Rank("default", "&7", "Player", ChatColor.GRAY, 0, true));
    }

    private void createIfNotExists(String name, Rank rank) { if (!new File(ranksFolder, name + ".yml").exists()) saveRank(rank); }
    public void saveRank(Rank rank) { try { YamlConfiguration cfg = new YamlConfiguration(); cfg.set("rank", rank); cfg.save(new File(ranksFolder, rank.getName() + ".yml")); ranks.put(rank.getName(), rank); } catch (Exception e) { e.printStackTrace(); } }
    public void loadRanks() { ranks.clear(); for (File f : ranksFolder.listFiles((d, n) -> n.endsWith(".yml"))) try { YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f); Rank r = (Rank) cfg.get("rank"); if (r != null) ranks.put(r.getName(), r); } catch (Exception e) { plugin.getLogger().warning("Failed to load rank from " + f.getName()); } plugin.getLogger().info("Loaded " + ranks.size() + " ranks"); }
    public void setPlayerRank(Player p, String name) { Rank r = ranks.getOrDefault(name, getDefaultRank()); playerRanks.put(p.getUniqueId(), r); savePlayerRank(p); }
    public Rank getPlayerRank(Player p) { return playerRanks.getOrDefault(p.getUniqueId(), getDefaultRank()); }
    public void savePlayerRank(Player p) { try { YamlConfiguration cfg = new YamlConfiguration(); Rank r = playerRanks.get(p.getUniqueId()); if (r != null) cfg.set("rank", r.getName()); cfg.save(new File(playerDataFolder, p.getUniqueId() + ".yml")); } catch (Exception e) { e.printStackTrace(); } }
    public void loadPlayerRank(Player p) { File f = new File(playerDataFolder, p.getUniqueId() + ".yml"); if (f.exists()) try { YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f); String name = cfg.getString("rank"); playerRanks.put(p.getUniqueId(), name != null ? ranks.getOrDefault(name, getDefaultRank()) : getDefaultRank()); } catch (Exception e) { playerRanks.put(p.getUniqueId(), getDefaultRank()); } else playerRanks.put(p.getUniqueId(), getDefaultRank()); }
    public Rank getDefaultRank() { return ranks.values().stream().filter(Rank::isDefault).findFirst().orElse(ranks.get("default")); }
    public Rank getRank(String name) { return ranks.get(name); }
    public Map<String, Rank> getRanks() { return new HashMap<>(ranks); }
}