package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class GameListener implements Listener {
    private final KokoPixel plugin;
    public GameListener(KokoPixel plugin) { this.plugin = plugin; }

    private boolean isLobby(String world) {
        return world.equals(plugin.getConfig().getString("lobby.world", "world"));
    }

    private boolean isSpectator(Player p) {
        return plugin.getSpectatorManager().isSpectator(p);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        String world = e.getPlayer().getWorld().getName();
        if (isLobby(world) || isSpectator(e.getPlayer())) { e.setCancelled(true); return; }
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId ->
            plugin.getReplayManager().recordBlockChange(gameId,
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(), "AIR"));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        String world = e.getPlayer().getWorld().getName();
        if (isLobby(world) || isSpectator(e.getPlayer())) { e.setCancelled(true); return; }
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId ->
            plugin.getReplayManager().recordBlockChange(gameId,
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(),
                e.getBlock().getType().name()));
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        // Spectators can right-click their spectator items (paper, bed, etc.)
        // but must not interact with the world itself
        if (!isSpectator(e.getPlayer())) return;
        if (e.getClickedBlock() != null) e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent e) {
        if (isSpectator(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickupItem(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isSpectator(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isSpectator(p)) return;
        // Allow clicking their own spectator inventory (hotbar items) but block chest/container access
        if (e.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            e.setCancelled(true);
        }
    }

    @EventHandler public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (isLobby(e.getPlayer().getWorld().getName()) || isSpectator(e.getPlayer()))
            e.setCancelled(true);
    }

    @EventHandler public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && (isLobby(p.getWorld().getName()) || isSpectator(p))) {
            e.setCancelled(true);
            p.setFoodLevel(20);
        }
    }

    @EventHandler public void onEntityDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && (isLobby(p.getWorld().getName()) || isSpectator(p)))
            e.setCancelled(true);
    }
}
