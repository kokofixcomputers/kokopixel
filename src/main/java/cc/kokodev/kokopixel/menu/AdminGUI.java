package cc.kokodev.kokopixel.menu;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.GameInstanceImpl;
import cc.kokodev.kokopixel.api.game.GameState;
import cc.kokodev.kokopixel.replay.ReplayIndexEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AdminGUI implements Listener {

    private static final String TITLE_MAIN    = "§8§lAdmin Panel";
    private static final String TITLE_GAMES   = "§8§lActive Games";
    private static final String TITLE_REPLAYS = "§8§lAll Replays";
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    private final Map<UUID, Integer> gamePages   = new HashMap<>();
    private final Map<UUID, Integer> replayPages = new HashMap<>();
    private final Map<UUID, List<ReplayIndexEntry>> replayLists = new HashMap<>();

    public AdminGUI(KokoPixel plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // -------------------------------------------------------------------------
    // Open methods
    // -------------------------------------------------------------------------

    public void openMain(Player admin) {
        Inventory inv = Bukkit.createInventory(null, 27, legacy.deserialize(TITLE_MAIN));

        inv.setItem(10, makeItem(Material.COMPASS,
            "§b§lActive Games",
            List.of("§7View and spectate", "§7currently running games.")));

        inv.setItem(13, makeItem(Material.PLAYER_HEAD,
            "§e§lServer Info",
            List.of(
                "§7Online: §f" + plugin.getServer().getOnlinePlayers().size(),
                "§7Active games: §f" + countActiveGames(),
                "§7In queue: §f" + plugin.getQueueManager().getTotalQueueSize()
            )));

        inv.setItem(16, makeItem(Material.BOOK,
            "§d§lAll Replays",
            List.of("§7Browse every replay", "§7stored across all servers.")));

        inv.setItem(22, makeItem(Material.BARRIER,
            "§c§lStop All Games",
            List.of("§7Force-end every active game.", "§c§lUse with caution!")));

        player(admin, inv);
    }

    public void openGamesList(Player admin, int page) {
        gamePages.put(admin.getUniqueId(), page);
        List<GameInstanceImpl> games = getActiveGames();
        int totalPages = Math.max(1, (int) Math.ceil(games.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
            legacy.deserialize(TITLE_GAMES + " §7(" + (page + 1) + "/" + totalPages + ")"));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, games.size());
        for (int i = start; i < end; i++) {
            GameInstanceImpl game = games.get(i);
            inv.setItem(i - start, makeItem(Material.GRASS_BLOCK,
                "§a§l" + game.getGameType() + " §7#" + game.getGameId().toString().substring(0, 6),
                List.of(
                    "§7Type: §f" + game.getGameType(),
                    "§7State: §f" + game.getState().name(),
                    "§7Players: §f" + game.getPlayers().size(),
                    "§7World: §f" + game.getWorld().getName(),
                    "", "§eClick to spectate")));
        }

        if (page > 0)  inv.setItem(45, makeItem(Material.ARROW, "§7Previous Page", List.of("§7Page " + page)));
        inv.setItem(49, makeItem(Material.DARK_OAK_DOOR, "§c§lBack", List.of("§7Return to admin panel")));
        if (page < totalPages - 1) inv.setItem(53, makeItem(Material.ARROW, "§7Next Page", List.of("§7Page " + (page + 2))));

        player(admin, inv);
    }

    public void openReplaysList(Player admin, int page) {
        Map<String, List<ReplayIndexEntry>> remoteIndex = Collections.emptyMap();
        List<ReplayIndexEntry> all = plugin.getReplayManager().getAllKnownReplays(remoteIndex);

        replayPages.put(admin.getUniqueId(), page);
        replayLists.put(admin.getUniqueId(), all);

        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
            legacy.deserialize(TITLE_REPLAYS + " §7(" + (page + 1) + "/" + totalPages + ")"));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, all.size());
        for (int i = start; i < end; i++) {
            ReplayIndexEntry e = all.get(i);
            long mins = e.durationSeconds / 60, secs = e.durationSeconds % 60;
            String date = DATE_FMT.format(Instant.ofEpochMilli(e.recordedAt));
            long expiresInHours = Math.max(0,
                    (6 * 3600_000L - (System.currentTimeMillis() - e.recordedAt)) / 3600_000L);
            boolean local = e.isLocal(plugin.getServerId());

            inv.setItem(i - start, makeItem(
                local ? Material.BOOK : Material.WRITABLE_BOOK,
                "§d§l" + e.gameType + " §7" + date,
                List.of(
                    "§7Server: §f" + e.hostServerId + (local ? " §a(this server)" : " §e(remote)"),
                    "§7Duration: §f" + mins + "m " + secs + "s",
                    "§7Players: §f" + e.participantCount,
                    "§7Expires in: §e" + expiresInHours + "h",
                    "",
                    local ? "§eClick to watch" : "§eClick to route & watch"
                )));
        }

        if (all.isEmpty())
            inv.setItem(22, makeItem(Material.BARRIER, "§cNo replays found", List.of("§7No recordings exist yet.")));

        if (page > 0)  inv.setItem(45, makeItem(Material.ARROW, "§7Previous Page", List.of("§7Page " + page)));
        inv.setItem(49, makeItem(Material.DARK_OAK_DOOR, "§c§lBack", List.of("§7Return to admin panel")));
        if (page < totalPages - 1) inv.setItem(53, makeItem(Material.ARROW, "§7Next Page", List.of("§7Page " + (page + 2))));

        player(admin, inv);
    }

    // -------------------------------------------------------------------------
    // Click handler
    // -------------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player admin)) return;
        if (!admin.hasPermission("kokopixel.admin")) return;

        String title = legacy.serialize(e.getView().title());

        // Only handle our own GUIs — don't cancel clicks in other inventories
        if (!title.equals(TITLE_MAIN) && !title.startsWith(TITLE_GAMES) && !title.startsWith(TITLE_REPLAYS)) return;

        e.setCancelled(true);

        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        // --- Main panel ---
        if (title.equals(TITLE_MAIN)) {
            switch (e.getSlot()) {
                case 10 -> openGamesList(admin, 0);
                case 16 -> openReplaysList(admin, 0);
                case 22 -> {
                    plugin.getMinigameManager().shutdown();
                    admin.sendMessage(legacy.deserialize("§cAll active games have been stopped."));
                    admin.closeInventory();
                }
            }
            return;
        }

        // --- Games list ---
        if (title.startsWith(TITLE_GAMES)) {
            int page = gamePages.getOrDefault(admin.getUniqueId(), 0);
            if (e.getSlot() == 49) { openMain(admin); return; }
            if (e.getSlot() == 45) { openGamesList(admin, page - 1); return; }
            if (e.getSlot() == 53) { openGamesList(admin, page + 1); return; }

            List<GameInstanceImpl> games = getActiveGames();
            int idx = page * PAGE_SIZE + e.getSlot();
            if (idx < 0 || idx >= games.size()) return;
            GameInstanceImpl game = games.get(idx);
            if (game.getState() == GameState.ENDED) {
                admin.sendMessage(legacy.deserialize("§cThat game has already ended."));
                openGamesList(admin, page);
                return;
            }
            admin.closeInventory();
            spectateGame(admin, game);
            return;
        }

        // --- Replays list ---
        if (title.startsWith(TITLE_REPLAYS)) {
            int page = replayPages.getOrDefault(admin.getUniqueId(), 0);
            if (e.getSlot() == 49) { openMain(admin); return; }
            if (e.getSlot() == 45) { openReplaysList(admin, page - 1); return; }
            if (e.getSlot() == 53) { openReplaysList(admin, page + 1); return; }

            List<ReplayIndexEntry> list = replayLists.getOrDefault(admin.getUniqueId(), Collections.emptyList());
            int idx = page * PAGE_SIZE + e.getSlot();
            if (idx < 0 || idx >= list.size()) return;

            ReplayIndexEntry entry = list.get(idx);
            admin.closeInventory();

            if (entry.isLocal(plugin.getServerId())) {
                plugin.getReplayManager().startOrJoinSession(admin, entry.gameId);
            } else {
                if (!plugin.isBungeeEnabled() || plugin.getBungeeListener() == null) {
                    admin.sendMessage(legacy.deserialize("§c[Replay] BungeeCord is not enabled on this server."));
                    return;
                }
                admin.sendMessage(legacy.deserialize(
                    "§7[§dReplay§7] §eRouting you to §f" + entry.hostServerId + "§e to watch this replay..."));
                plugin.getBungeeListener().routePlayerToReplay(admin, entry.hostServerId, entry.gameId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Spectate logic
    // -------------------------------------------------------------------------

    private void spectateGame(Player admin, GameInstanceImpl game) {
        if (plugin.getSpectatorManager().isSpectator(admin))
            plugin.getSpectatorManager().disableSpectator(admin);
        admin.setMetadata("kp_admin_spectate",
            new org.bukkit.metadata.FixedMetadataValue(plugin, game.getGameId().toString()));
        admin.teleport(game.getWorld().getSpawnLocation());
        plugin.getSpectatorManager().enableSpectator(admin, true);
        admin.sendMessage(legacy.deserialize(
            "§7[§bAdmin§7] §eSpectating §f" + game.getGameType()
            + " §7(" + game.getPlayers().size() + " players)"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<GameInstanceImpl> getActiveGames() {
        return plugin.getMinigameManager().getActiveGames().stream()
            .filter(g -> g.getState() != GameState.ENDED)
            .sorted(Comparator.comparing(GameInstanceImpl::getGameType))
            .toList();
    }

    private int countActiveGames() {
        return (int) plugin.getMinigameManager().getActiveGames().stream()
            .filter(g -> g.getState() != GameState.ENDED).count();
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
            .map(l -> legacy.deserialize(l).decoration(TextDecoration.ITALIC, false))
            .toList());
        item.setItemMeta(meta);
        return item;
    }

    private void player(Player p, Inventory inv) {
        Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(inv));
    }
}
