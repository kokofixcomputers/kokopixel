package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.ranks.Rank;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerListener implements Listener {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public PlayerListener(KokoPixel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getRankManager().loadPlayerRank(player);
        Rank rank = plugin.getRankManager().getPlayerRank(player);
        plugin.getFriendManager().loadFriends(player.getUniqueId(), player.getName());

        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20);
        player.setFoodLevel(20);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.getInventory().clear();
        player.teleport(plugin.getLobbySpawn());

        plugin.getMinigameManager().removePlayer(player);
        plugin.getGameSelectorMenu().giveGameSelector(player);

        // Handle pending cross-server actions (party warp for queue or replay)
        if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null) {
            String pendingGame = plugin.getBungeeListener().consumePendingQueue(player.getUniqueId());
            if (pendingGame != null) {
                final String game = pendingGame;
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getQueueManager().addToQueue(player,
                        plugin.getMinigameManager().getMinigame(game)), 20L);
                return; // skip normal replay check
            }
            UUID pendingReplayId = plugin.getBungeeListener().consumePendingReplay(player.getUniqueId());
            if (pendingReplayId != null) {
                final UUID rid = pendingReplayId;
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getReplayManager().startOrJoinSession(player, rid), 20L);
                return;
            }
        }

        // Check if this player was routed here to watch a replay (direct path)
        plugin.getReplayManager().checkPendingStart(player);

        String joinMsg = ChatColor.translateAlternateColorCodes('&', "&8[&a+&8] " + rank.getFormattedPrefix() + " " + rank.getColor() + player.getName() + "&r &7joined!");
        event.joinMessage(legacy.deserialize(joinMsg));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Rank rank = plugin.getRankManager().getPlayerRank(player);
        plugin.getQueueManager().removeFromQueue(player);
        plugin.getMinigameManager().removePlayer(player);
        if (plugin.isSpectating(player)) plugin.disableSpectator(player);
        plugin.getRankManager().savePlayerRank(player);
        plugin.getFriendManager().unload(player.getUniqueId());
        String quitMsg = ChatColor.translateAlternateColorCodes('&', "&8[&c-&8] " + rank.getFormattedPrefix() + " " + rank.getColor() + player.getName() + "&r &7left!");
        event.quitMessage(legacy.deserialize(quitMsg));
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) && event.getItem() != null && event.getItem().getType() == Material.COMPASS) {
            event.setCancelled(true);
            plugin.getGameSelectorMenu().open(event.getPlayer());
        }
    }
}