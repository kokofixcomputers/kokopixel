package cc.kokodev.kokopixel.spectator;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

public class SpectatorListener implements Listener {
    private final KokoPixel plugin;
    private final SpectatorManager sm;

    public SpectatorListener(KokoPixel plugin) { this.plugin = plugin; this.sm = plugin.getSpectatorManager(); }

    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && sm.isSpectator(p)) e.setCancelled(true); }
    @EventHandler public void onMove(PlayerMoveEvent e) { Player p = e.getPlayer(); if (sm.isFrozenSpectator(p) && e.getFrom().distanceSquared(e.getTo()) > 0.001) e.setTo(e.getFrom()); }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!sm.isSpectator(p)) return;
        ItemStack item = e.getItem();
        if (item == null) return;
        if (item.getType() == Material.PAPER) {
            e.setCancelled(true);
            if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) sm.cycleSpectator(p, true);
            else if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) sm.cycleSpectator(p, false);
        } else if (item.getType() == Material.RED_BED) {
            e.setCancelled(true);
            // Check if this player is watching a replay — exit the session instead
            if (p.hasMetadata("kp_replay_session")) {
                plugin.getReplayManager().getSessionFor(p.getUniqueId())
                        .ifPresent(session -> session.removeViewer(p));
            } else {
                sm.disableSpectator(p);
                p.teleport(plugin.getLobbySpawn());
                plugin.getGameSelectorMenu().giveGameSelector(p);
            }
        } else if (item.getType() == Material.PAPER && item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("Requeue")) {
            e.setCancelled(true);
            if (p.hasMetadata("kp_last_game")) {
                String game = p.getMetadata("kp_last_game").get(0).asString();
                sm.disableSpectator(p);
                p.teleport(plugin.getLobbySpawn());
                plugin.getQueueManager().addToQueue(p, plugin.getMinigameManager().getMinigame(game));
            } else { sm.disableSpectator(p); p.teleport(plugin.getLobbySpawn()); plugin.getGameSelectorMenu().giveGameSelector(p); }
        } else if (item.getType() == Material.SUGAR) {
            e.setCancelled(true);
            float speed = Math.min(1.0f, p.getFlySpeed() + 0.1f);
            p.setFlySpeed(speed);
            p.sendMessage("§aFlight speed: §f" + Math.round(speed * 10));
        } else if (item.getType() == Material.SLIME_BALL) {
            e.setCancelled(true);
            float speed = Math.max(0.1f, p.getFlySpeed() - 0.1f);
            p.setFlySpeed(speed);
            p.sendMessage("§aFlight speed: §f" + Math.round(speed * 10));
        }
    }
}