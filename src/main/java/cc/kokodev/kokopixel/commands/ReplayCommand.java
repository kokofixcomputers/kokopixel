package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.replay.EnhancedReplayRecording;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ReplayCommand implements CommandExecutor, Listener {

    private static final String TITLE_PREFIX = "§d§lReplays";
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
    private final Map<UUID, Integer> pages = new HashMap<>();
    // Maps inventory title -> list of recordings shown (so we know which slot = which recording)
    private final Map<UUID, List<EnhancedReplayRecording>> openLists = new HashMap<>();

    public ReplayCommand(KokoPixel plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        openList(player, 0);
        return true;
    }

    private void openList(Player player, int page) {
        List<EnhancedReplayRecording> list = plugin.getReplayManager().getRecordingsFor(player.getUniqueId());
        pages.put(player.getUniqueId(), page);
        openLists.put(player.getUniqueId(), list);

        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = TITLE_PREFIX + " §7(" + (page + 1) + "/" + totalPages + ")";
        Inventory inv = Bukkit.createInventory(null, 54, legacy.deserialize(title));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, list.size());

        for (int i = start; i < end; i++) {
            EnhancedReplayRecording r = list.get(i);
            long mins = r.durationSeconds() / 60;
            long secs = r.durationSeconds() % 60;
            String date = DATE_FMT.format(Instant.ofEpochMilli(r.recordedAt));
            long expiresInHours = Math.max(0,
                    (6 * 3600_000L - (System.currentTimeMillis() - r.recordedAt)) / 3600_000L);

            List<String> lore = List.of(
                    "§7Game: §f" + r.gameType,
                    "§7Date: §f" + date,
                    "§7Duration: §f" + mins + "m " + secs + "s",
                    "§7Players: §f" + r.participants.size(),
                    "§7Expires in: §e" + expiresInHours + "h",
                    "",
                    "§eClick to watch"
            );
            inv.setItem(i - start, makeItem(Material.BOOK, "§d§l" + r.gameType + " §7" + date, lore));
        }

        if (list.isEmpty()) {
            inv.setItem(22, makeItem(Material.BARRIER, "§cNo replays found",
                    List.of("§7You haven't played any games yet,", "§7or all replays have expired.")));
        }

        if (page > 0)
            inv.setItem(45, makeItem(Material.ARROW, "§7Previous Page", List.of("§7Page " + page)));
        if (page < totalPages - 1)
            inv.setItem(53, makeItem(Material.ARROW, "§7Next Page", List.of("§7Page " + (page + 2))));

        Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(inv));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        String title = legacy.serialize(e.getView().title());
        if (!title.startsWith(TITLE_PREFIX)) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        int page = pages.getOrDefault(player.getUniqueId(), 0);
        int slot = e.getSlot();

        if (slot == 45) { openList(player, page - 1); return; }
        if (slot == 53) { openList(player, page + 1); return; }

        List<EnhancedReplayRecording> list = openLists.getOrDefault(player.getUniqueId(), Collections.emptyList());
        int idx = page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= list.size()) return;

        EnhancedReplayRecording recording = list.get(idx);
        player.closeInventory();

        // Check not already in a game or queue
        if (plugin.isInGame(player)) { player.sendMessage("§c[Replay] Leave your current game first."); return; }
        if (plugin.isInQueue(player)) { player.sendMessage("§c[Replay] Leave your current queue first."); return; }

        plugin.getReplayManager().startOrJoinSession(player, recording.gameId);
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
}
