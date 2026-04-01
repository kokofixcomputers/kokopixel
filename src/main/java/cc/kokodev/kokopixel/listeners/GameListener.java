package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

public class GameListener implements Listener {
    private final KokoPixel plugin;
    public GameListener(KokoPixel plugin) { this.plugin = plugin; }

    private boolean isLobby(String world) { return world.equals(plugin.getConfig().getString("lobby.world", "world")); }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        String world = e.getPlayer().getWorld().getName();
        if (isLobby(world)) { e.setCancelled(true); return; }
        // Forward to replay recorder
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId ->
            plugin.getReplayManager().recordBlockChange(gameId,
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(), "AIR"));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        String world = e.getPlayer().getWorld().getName();
        if (isLobby(world)) { e.setCancelled(true); return; }
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId ->
            plugin.getReplayManager().recordBlockChange(gameId,
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(),
                e.getBlock().getType().name()));
    }

    @EventHandler public void onPlayerDropItem(PlayerDropItemEvent e) { if (isLobby(e.getPlayer().getWorld().getName())) e.setCancelled(true); }
    @EventHandler public void onFoodLevelChange(FoodLevelChangeEvent e) { if (e.getEntity() instanceof org.bukkit.entity.Player p && isLobby(p.getWorld().getName())) { e.setCancelled(true); p.setFoodLevel(20); } }
    @EventHandler public void onEntityDamage(EntityDamageEvent e) { if (e.getEntity() instanceof org.bukkit.entity.Player p && isLobby(p.getWorld().getName())) e.setCancelled(true); }
}