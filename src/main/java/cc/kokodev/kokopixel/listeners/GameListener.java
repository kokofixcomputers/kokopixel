package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.Material;
import java.util.Optional;
import cc.kokodev.kokopixel.replay.EnhancedReplaySession;


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
        // Only record if the break actually happens (not cancelled by another plugin)
        // Use MONITOR priority so we see the final cancelled state
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakMonitor(BlockBreakEvent e) {
        String world = e.getPlayer().getWorld().getName();
        if (isLobby(world) || isSpectator(e.getPlayer())) return;
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId ->
            plugin.getReplayManager().recordBlockChange(gameId,
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(), 
                e.getBlock().getType().name(), "AIR"));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        String world = e.getPlayer().getWorld().getName();
        if (isLobby(world) || isSpectator(e.getPlayer())) return;
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId ->
            plugin.getReplayManager().recordBlockChange(gameId,
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(),
                e.getBlockReplacedState().getType().name(),
                e.getBlock().getType().name()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        String world = e.getLocation().getWorld().getName();
        if (isLobby(world)) return;
        plugin.getMinigameManager().getGameIdForWorld(world).ifPresent(gameId -> {
            for (org.bukkit.block.Block block : e.blockList()) {
                plugin.getReplayManager().recordBlockChange(gameId,
                        block.getX(), block.getY(), block.getZ(), 
                        block.getType().name(), "AIR");
            }
        });
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        // Spectators can only use their replay controls
        if (!isSpectator(e.getPlayer())) return;

        Player player = e.getPlayer();
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
        
        // Handle compass right-click - open player teleport GUI
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || 
            e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            if (item != null && item.getType() == Material.COMPASS) {
                openTeleportGUI(player);
                e.setCancelled(true);
                return;
            }
        }
        
        // Handle play/pause head right-click - toggle pause/play
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || 
            e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                toggleReplayPause(player);
                e.setCancelled(true);
                return;
            }
        }
        
        // Handle bed click - leave replay
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || 
            e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            if (item != null && item.getType() == Material.RED_BED) {
                leaveReplay(player);
                e.setCancelled(true);
                return;
            }
        }
        
        // Handle play/pause head left-click - skip back 60 ticks
        if (e.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_AIR || 
            e.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                skipReplayFrames(player, -60); // Skip back 60 frames
                e.setCancelled(true);
                return;
            }
        }
        
        // Block all other interactions
        if (e.getClickedBlock() != null) e.setCancelled(true);
    }

    private void openTeleportGUI(Player player) {
        Optional<EnhancedReplaySession> sessionOpt = plugin.getReplayManager().getSessionFor(player.getUniqueId());
        if (sessionOpt.isEmpty()) return;
        
        EnhancedReplaySession session = sessionOpt.get();
        java.util.List<UUID> playerIds = new java.util.ArrayList<>(session.getRecording().participants);
        
        // Create GUI with player heads
        int size = Math.min(54, playerIds.size()); // Max chest size
        org.bukkit.inventory.Inventory gui = plugin.getServer().createInventory(null, size, "§6§lTeleport to Player");
        
        for (int i = 0; i < size; i++) {
            UUID playerId = playerIds.get(i);
            String playerName = session.getRecording().participants.size() > i ? 
                "Unknown Player" : "Player " + (i + 1);
            
            org.bukkit.inventory.ItemStack head = new org.bukkit.inventory.ItemStack(Material.PLAYER_HEAD);
            var meta = head.getItemMeta();
            meta.setDisplayName(net.kyori.adventure.text.Component.text(playerName));
            head.setItemMeta(meta);
            gui.setItem(i, head);
        }
        
        player.openInventory(gui);
    }

    private void toggleReplayPause(Player player) {
        Optional<EnhancedReplaySession> sessionOpt = plugin.getReplayManager().getSessionFor(player.getUniqueId());
        if (sessionOpt.isEmpty()) return;
        
        EnhancedReplaySession session = sessionOpt.get();
        if (session.isPaused()) {
            session.resume();
            player.sendMessage("§aReplay resumed");
        } else {
            session.pause();
            player.sendMessage("§cReplay paused");
        }
    }

    private void skipReplayFrames(Player player, int frames) {
        Optional<EnhancedReplaySession> sessionOpt = plugin.getReplayManager().getSessionFor(player.getUniqueId());
        if (sessionOpt.isEmpty()) return;
        
        EnhancedReplaySession session = sessionOpt.get();
        session.skipFrames(frames);
        player.sendMessage("§eSkipped " + Math.abs(frames) + " frames");
    }

    private void leaveReplay(Player player) {
        Optional<EnhancedReplaySession> sessionOpt = plugin.getReplayManager().getSessionFor(player.getUniqueId());
        if (sessionOpt.isEmpty()) return;
        
        EnhancedReplaySession session = sessionOpt.get();
        session.removeViewer(player);
        player.sendMessage("§cLeft replay");
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
