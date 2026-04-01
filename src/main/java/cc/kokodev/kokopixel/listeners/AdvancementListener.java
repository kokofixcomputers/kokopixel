package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

public class AdvancementListener implements Listener {
    private final KokoPixel plugin;
    public AdvancementListener(KokoPixel plugin) { this.plugin = plugin; }
    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent event) { event.message(null); }
}