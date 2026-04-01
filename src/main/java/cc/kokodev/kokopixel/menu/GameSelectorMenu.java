package cc.kokodev.kokopixel.menu;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.Minigame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameSelectorMenu implements Listener {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
    private final Map<Location, String> selectorBlocks = new ConcurrentHashMap<>();
    private final Map<String, Integer> remoteQueueSizes = new HashMap<>();

    public GameSelectorMenu(KokoPixel plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        loadSelectorBlocks();
    }

    private void loadSelectorBlocks() {
        if (plugin.getConfig().contains("selector-blocks")) {
            for (String key : plugin.getConfig().getConfigurationSection("selector-blocks").getKeys(false)) {
                String world = plugin.getConfig().getString("selector-blocks." + key + ".world");
                double x = plugin.getConfig().getDouble("selector-blocks." + key + ".x");
                double y = plugin.getConfig().getDouble("selector-blocks." + key + ".y");
                double z = plugin.getConfig().getDouble("selector-blocks." + key + ".z");
                String game = plugin.getConfig().getString("selector-blocks." + key + ".game");
                selectorBlocks.put(new Location(Bukkit.getWorld(world), x, y, z), game);
            }
        }
    }

    public void registerGameSelectorBlock(Location loc, String game) {
        selectorBlocks.put(loc, game);
        String key = loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
        plugin.getConfig().set("selector-blocks." + key + ".world", loc.getWorld().getName());
        plugin.getConfig().set("selector-blocks." + key + ".x", loc.getX());
        plugin.getConfig().set("selector-blocks." + key + ".y", loc.getY());
        plugin.getConfig().set("selector-blocks." + key + ".z", loc.getZ());
        plugin.getConfig().set("selector-blocks." + key + ".game", game);
        plugin.saveConfig();
    }

    public void removeGameSelectorBlock(Location loc) {
        selectorBlocks.remove(loc);
        String key = loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
        plugin.getConfig().set("selector-blocks." + key, null);
        plugin.saveConfig();
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, legacy.deserialize("§6§lGame Selector"));
        int slot = 0;
        for (Minigame mg : plugin.getMinigameManager().getMinigames()) {
            if (slot >= 54) break;
            ItemStack item = new ItemStack(Material.GRASS_BLOCK);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(legacy.deserialize(mg.getDisplayName()));
            int q = plugin.getQueueManager().getQueueSize(mg.getName()) + remoteQueueSizes.getOrDefault(mg.getName(), 0);
            meta.lore(Arrays.asList(
                Component.text(""),
                Component.text("Players: " + mg.getMinPlayers() + "-" + mg.getMaxPlayers(), NamedTextColor.GRAY),
                Component.text("In Queue: " + (q > 0 ? "§a" + q : "§c" + q)),
                Component.text(""),
                Component.text("Click to join!", NamedTextColor.YELLOW)
            ));
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        if (plugin.isBungeeEnabled()) {
            ItemStack compass = new ItemStack(Material.RECOVERY_COMPASS);
            ItemMeta meta = compass.getItemMeta();
            meta.displayName(Component.text("Server Selector", NamedTextColor.AQUA));
            compass.setItemMeta(meta);
            inv.setItem(53, compass);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!e.getView().title().equals(legacy.deserialize("§6§lGame Selector"))) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;
        Player p = (Player) e.getWhoClicked();
        int slot = e.getSlot();
        if (slot == 53 && plugin.isBungeeEnabled()) {
            p.sendMessage(legacy.deserialize("§bServer list coming soon!"));
            return;
        }
        List<Minigame> games = new ArrayList<>(plugin.getMinigameManager().getMinigames());
        if (slot >= games.size()) return;
        Minigame mg = games.get(slot);
        if (plugin.isInGame(p)) { p.sendMessage(legacy.deserialize("§cYou're already in a game!")); p.closeInventory(); return; }
        p.closeInventory();
        plugin.getQueueManager().addToQueue(p, mg);
    }

    public void giveGameSelector(Player p) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(legacy.deserialize("§a§lGame Selector §7(Right Click)"));
        compass.setItemMeta(meta);
        p.getInventory().setItem(0, compass);
        if (plugin.isBungeeEnabled()) {
            ItemStack server = new ItemStack(Material.RECOVERY_COMPASS);
            ItemMeta serverMeta = server.getItemMeta();
            serverMeta.displayName(legacy.deserialize("§b§lServer Selector §7(Right Click)"));
            server.setItemMeta(serverMeta);
            p.getInventory().setItem(1, server);
        }
    }

    public void updateRemoteQueueSize(String game, int size) { remoteQueueSizes.put(game, size); }
}