package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

public class StatisticsListener implements Listener {
    private final KokoPixel plugin;
    public StatisticsListener(KokoPixel plugin) { this.plugin = plugin; }
    @EventHandler public void onStatisticIncrement(PlayerStatisticIncrementEvent event) { event.setCancelled(true); }
}