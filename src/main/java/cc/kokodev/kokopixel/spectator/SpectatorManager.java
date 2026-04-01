package cc.kokodev.kokopixel.spectator;

import cc.kokodev.kokopixel.KokoPixel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class SpectatorManager {
    private final KokoPixel plugin;
    private final Set<UUID> spectators = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public SpectatorManager(KokoPixel plugin) { this.plugin = plugin; }

    public void enableSpectator(Player p, boolean canReturn) {
        spectators.add(p.getUniqueId());
        storeState(p);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setInvulnerable(true);
        p.setCollidable(false);
        p.setCanPickupItems(false);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
        giveItems(p, canReturn);
        p.sendMessage(legacy.deserialize("§7[§bSpectator§7] §eYou are now spectating! Use paper to teleport, bed to return."));
    }

    public void enableFrozenSpectator(Player p, boolean canReturn) { enableSpectator(p, canReturn); frozen.add(p.getUniqueId()); p.setWalkSpeed(0); p.setFlySpeed(0); }
    public void disableSpectator(Player p) { spectators.remove(p.getUniqueId()); frozen.remove(p.getUniqueId()); p.removePotionEffect(PotionEffectType.INVISIBILITY); p.removePotionEffect(PotionEffectType.NIGHT_VISION); p.setGameMode(GameMode.SURVIVAL); p.setAllowFlight(false); p.setFlying(false); p.setInvulnerable(false); p.setCollidable(true); p.setCanPickupItems(true); p.setWalkSpeed(0.2f); p.setFlySpeed(0.1f); p.getInventory().clear(); restoreState(p); }
    public boolean isSpectator(Player p) { return spectators.contains(p.getUniqueId()); }
    public boolean isFrozenSpectator(Player p) { return frozen.contains(p.getUniqueId()); }

    public void teleportToPlayer(Player spectator, Player target) {
        if (!isSpectator(spectator)) { spectator.sendMessage(legacy.deserialize("§cYou must be in spectator mode!")); return; }
        spectator.teleport(target.getLocation());
        spectator.sendMessage(legacy.deserialize("§7Teleported to §e" + target.getName()));
    }

    public void cycleSpectator(Player spectator, boolean next) {
        if (!isSpectator(spectator)) return;
        List<Player> players = new java.util.ArrayList<>(plugin.getServer().getOnlinePlayers());
        players.remove(spectator);
        if (players.isEmpty()) { spectator.sendMessage(legacy.deserialize("§cNo other players online!")); return; }
        Player current = players.stream().filter(p -> p.getWorld().equals(spectator.getWorld()) && p.getLocation().distance(spectator.getLocation()) < 10).findFirst().orElse(null);
        int idx = current != null ? players.indexOf(current) : -1;
        int newIdx = next ? (idx + 1) % players.size() : (idx - 1 + players.size()) % players.size();
        teleportToPlayer(spectator, players.get(newIdx));
    }

    public void cleanup() { for (UUID id : spectators) { Player p = plugin.getServer().getPlayer(id); if (p != null) disableSpectator(p); } spectators.clear(); frozen.clear(); }

    private void giveItems(Player p, boolean canReturn) {
        ItemStack paper = new ItemStack(Material.PAPER); ItemMeta pm = paper.getItemMeta(); pm.displayName(Component.text("§b§lTeleport to Players", NamedTextColor.AQUA)); pm.lore(java.util.Arrays.asList(Component.text("§7Left-click: Previous player"), Component.text("§7Right-click: Next player"))); paper.setItemMeta(pm); p.getInventory().setItem(0, paper);
        ItemStack bed = new ItemStack(Material.RED_BED); ItemMeta bm = bed.getItemMeta(); bm.displayName(Component.text("§c§lReturn to Lobby", NamedTextColor.RED)); bed.setItemMeta(bm); p.getInventory().setItem(8, bed);
        if (canReturn) { ItemStack req = new ItemStack(Material.PAPER); ItemMeta rm = req.getItemMeta(); rm.displayName(Component.text("§a§lRequeue & Return", NamedTextColor.GREEN)); rm.lore(java.util.Arrays.asList(Component.text("§7Return to lobby and requeue"))); req.setItemMeta(rm); p.getInventory().setItem(1, req); }
        ItemStack up = new ItemStack(Material.SUGAR); ItemMeta upm = up.getItemMeta(); upm.displayName(Component.text("§b§lIncrease Speed", NamedTextColor.AQUA)); up.setItemMeta(upm); p.getInventory().setItem(4, up);
        ItemStack down = new ItemStack(Material.SLIME_BALL); ItemMeta downm = down.getItemMeta(); downm.displayName(Component.text("§e§lDecrease Speed", NamedTextColor.YELLOW)); down.setItemMeta(downm); p.getInventory().setItem(5, down);
    }

    private void storeState(Player p) { p.setMetadata("kp_prev_gm", new org.bukkit.metadata.FixedMetadataValue(plugin, p.getGameMode())); p.setMetadata("kp_prev_flight", new org.bukkit.metadata.FixedMetadataValue(plugin, p.getAllowFlight())); }
    private void restoreState(Player p) { if (p.hasMetadata("kp_prev_gm")) { p.setGameMode((GameMode) p.getMetadata("kp_prev_gm").get(0).value()); p.removeMetadata("kp_prev_gm", plugin); } if (p.hasMetadata("kp_prev_flight")) { p.setAllowFlight(p.getMetadata("kp_prev_flight").get(0).asBoolean()); p.removeMetadata("kp_prev_flight", plugin); } }
}