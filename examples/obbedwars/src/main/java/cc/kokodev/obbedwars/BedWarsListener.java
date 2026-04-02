package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.api.game.GameState;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.UUID;

public class BedWarsListener implements Listener {

    private final OBBedWars plugin;

    public BedWarsListener(OBBedWars plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Bed)) return;
        Player player = event.getPlayer();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        if (game.handleBedBreak(player, block)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.TNT) return;
        BedWarsGame game = getActiveGame(event.getPlayer());
        if (game == null) return;
        // Don't cancel — cancelling returns the item to inventory (infinite TNT exploit).
        // Instead let the block place, then replace it with air and spawn primed TNT.
        Block block = event.getBlock();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            block.setType(Material.AIR, false);
            block.getWorld().spawn(block.getLocation().add(0.5, 0, 0.5), TNTPrimed.class, tnt -> {
                tnt.setFuseTicks(40);
                tnt.setSource(event.getPlayer());
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click actions
        if (!event.getAction().toString().contains("RIGHT")) return;
        
        Player player = event.getPlayer();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        
        // Check if we're holding a fireball
        ItemStack item = event.getItem();
        if (item == null || !BedWarsGame.isFireball(item)) return;
        
        // Cancel the event so the fire charge isn't placed
        event.setCancelled(true);
        
        // Only handle main hand or offhand interactions (avoid double throws)
        if (event.getHand() == EquipmentSlot.HAND || event.getHand() == EquipmentSlot.OFF_HAND) {
            // Consume one fireball
            int amount = item.getAmount();
            if (amount <= 1) {
                player.getInventory().setItem(event.getHand() == EquipmentSlot.HAND 
                    ? player.getInventory().getHeldItemSlot() 
                    : 40, null);
            } else {
                item.setAmount(amount - 1);
            }
            
            // Launch the fireball like a dispenser would
            Fireball fireball = player.launchProjectile(Fireball.class);
            fireball.setVelocity(player.getLocation().getDirection().multiply(1.5));
            fireball.setShooter(player);
            fireball.setYield(2.0f); // Explosion power
            fireball.setIsIncendiary(false); // Don't set blocks on fire
            
            // Play sound effect
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GHAST_SHOOT, 0.5f, 1.2f);
            
            // Add cooldown to prevent spam
            player.setCooldown(Material.FIRE_CHARGE, 20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        BedWarsGame game = getGameInWorld(event.getLocation().getWorld());
        if (game == null) return;
        
        // Protect beds from explosion damage
        event.blockList().removeIf(block -> block.getBlockData() instanceof Bed);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        BedWarsGame game = getGameInWorld(event.getBlock().getWorld());
        if (game == null) return;
        event.blockList().removeIf(block -> block.getBlockData() instanceof Bed);
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        // Play explosion effect when fireball hits something
        if (!(event.getEntity() instanceof Fireball fireball)) return;
        
        BedWarsGame game = getGameInWorld(event.getEntity().getWorld());
        if (game == null) return;
        
        Location hitLoc = event.getEntity().getLocation();
        hitLoc.getWorld().playSound(hitLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        hitLoc.getWorld().spawnParticle(Particle.EXPLOSION, hitLoc, 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBedEnter(PlayerBedEnterEvent event) {
        // Prevent players from sleeping in beds during a game — beds are game objects, not sleep points
        if (getActiveGame(event.getPlayer()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;

        event.setDeathMessage(null);
        event.getDrops().clear();
        event.setDroppedExp(0);

        String teamName = game.getPlayerTeamName(player);
        ChatColor teamColor = teamName != null
                ? game.getTeam(teamName).map(cc.kokodev.kokopixel.api.game.GameTeam::getColor).orElse(ChatColor.WHITE)
                : ChatColor.WHITE;
        game.broadcast(teamColor + player.getName() + " §7" + buildDeathMessage(event));

        // Force respawn immediately — dismisses the death screen without player input.
        // Must be scheduled 1 tick later so the death event fully processes first.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.spigot().respawn();
                // After respawn the player may be at lobby spawn — teleport back to game world,
                // then start the bed check + countdown.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        // Teleport back into the game world so the countdown happens in-world
                        player.teleport(game.getWorld().getSpawnLocation());
                        game.handlePlayerDeath(player);
                    }
                }, 2L);
            }
        }, 1L);
    }

    // PlayerRespawnEvent no longer needed — spigot().respawn() handles it directly.

    private String buildDeathMessage(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        if (lastDamage == null) return "died.";

        if (lastDamage instanceof EntityDamageByEntityEvent edbe) {
            Entity damager = edbe.getDamager();
            if (damager instanceof Arrow arrow && arrow.getShooter() instanceof Player killer)
                return "was shot by §e" + killer.getName() + "§7.";
            if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player killer)
                return "was blown up by §e" + killer.getName() + "§7.";
            if (damager instanceof Fireball fireball && fireball.getShooter() instanceof Player killer)
                return "was fireballed by §e" + killer.getName() + "§7.";
            if (damager instanceof Player killer)
                return "was slain by §e" + killer.getName() + "§7.";
        }

        return switch (lastDamage.getCause()) {
            case FALL                              -> "fell to their death.";
            case VOID                              -> "fell into the void.";
            case DROWNING                          -> "drowned.";
            case FIRE, FIRE_TICK                   -> "burned to death.";
            case LAVA                              -> "tried to swim in lava.";
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "was blown up.";
            case STARVATION                        -> "starved to death.";
            default                                -> "died.";
        };
    }

    private BedWarsGame getActiveGame(Player player) {
        return KokoPixel.getInstance().getMinigameManager()
                .getGame(player)
                .filter(g -> g instanceof BedWarsGame)
                .filter(g -> g.getState() == GameState.ACTIVE)
                .map(g -> (BedWarsGame) g)
                .orElse(null);
    }

    private BedWarsGame getGameInWorld(World world) {
        if (world == null) return null;
        return KokoPixel.getInstance().getMinigameManager().getActiveGames().stream()
                .filter(g -> g instanceof BedWarsGame)
                .filter(g -> g.getState() == GameState.ACTIVE)
                .filter(g -> g.getWorld().equals(world))
                .map(g -> (BedWarsGame) g)
                .findFirst()
                .orElse(null);
    }
}