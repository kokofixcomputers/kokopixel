package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.api.game.GameState;
import org.bukkit.*;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
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
        Player player = event.getPlayer();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        
        // Prevent spectators from breaking blocks
        if (game.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        
        Block block = event.getBlock();

        if (!(block.getBlockData() instanceof Bed)) return;
        if (game.handleBedBreak(player, block)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        
        // Prevent spectators from placing blocks
        if (game.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        
        if (event.getBlock().getType() != Material.TNT) return;
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

        // Block Zapper — handle before game check so it works for admins too
        ItemStack heldItem = event.getItem();
        if (heldItem != null && BedWarsGame.isBlockZapper(heldItem) && event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            Block target = event.getClickedBlock();
            if (target == null || target.getType() == Material.AIR) {
                target = player.getTargetBlockExact(5);
            }
            if (target == null || target.getType() == Material.AIR) {
                player.sendMessage("§cNo block in range!");
                return;
            }
            if (target.getBlockData() instanceof Bed) {
                player.sendMessage("§cYou can't zap a bed!");
                return;
            }
            target.getWorld().spawnParticle(Particle.FLASH, target.getLocation().add(0.5, 0.5, 0.5), 1);
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 2f);
            target.setType(Material.AIR);
            heldItem.setAmount(heldItem.getAmount() - 1);
            return;
        }

        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        
        // Prevent spectators from interacting
        if (game.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        
        // Check if we're holding a fireball
        ItemStack item = event.getItem();
        if (item == null) return;

        // Bed Healer — right-click to restore one bed health step
        if (BedWarsGame.isBedHealer(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return; // avoid double trigger
            if (game.healBed(player)) {
                // Consume the item
                item.setAmount(item.getAmount() - 1);
                player.sendMessage("§b§lBed Healer §7used — your bed gained 1 health!");
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
            } else {
                player.sendMessage("§cYour bed is already at full health or destroyed!");
            }
            return;
        }

        // Jedi Force Core - Push — right-click to push nearby enemies
        if (BedWarsGame.isForceCorePush(item)) {            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (player.getCooldown(Material.PAPER) > 0) return;
            int level = BedWarsGame.getForceCorePushLevel(item);
            double range    = switch (level) { case 2 -> 6.0; case 3 -> 10.0; default -> 3.0; };
            double force    = switch (level) { case 2 -> 5.0; case 3 -> 8.0;  default -> 3.0; };
            int    cooldown = switch (level) { case 2 -> 140; case 3 -> 100;  default -> 200; }; // ticks
            int    particles= switch (level) { case 2 -> 3;   case 3 -> 6;    default -> 1;   };
            String lvlTag   = level >= 2 ? " " + (level == 3 ? "III" : "II") : "";
            player.setCooldown(Material.PAPER, cooldown);
            String pusherTeam = game.getPlayerTeamName(player);
            int pushed = 0;
            // Include both real players AND bots (via game player list)
            for (cc.kokodev.kokopixel.api.game.GamePlayer gp : game.getPlayers()) {
                Player nearby = gp.getPlayer();
                if (nearby.equals(player)) continue;
                if (nearby.getLocation().distance(player.getLocation()) > range) continue;
                String nearbyTeam = game.getPlayerTeamName(nearby);
                if (pusherTeam != null && pusherTeam.equals(nearbyTeam)) continue;
                if (game.isSpectator(nearby.getUniqueId())) continue;
                org.bukkit.util.Vector dir = nearby.getLocation().toVector()
                        .subtract(player.getLocation().toVector())
                        .normalize().multiply(force).setY(0.4);
                // For bots, apply velocity to the shadow stand so isLaunched() detects it
                boolean isBot = KokoPixel.getInstance().getBotManager().isBot(nearby.getUniqueId());
                if (isBot) {
                    KokoPixel.getInstance().getBotManager().getHandle(nearby.getUniqueId())
                            .ifPresent(h -> {
                                // Teleport shadow stand by the force vector to simulate knockback
                                org.bukkit.Location newLoc = h.getLocation().clone().add(
                                        dir.getX() * 0.5, dir.getY() * 0.5, dir.getZ() * 0.5);
                                h.teleport(newLoc);
                                // Set velocity on CraftPlayer so isLaunched() detects it
                                nearby.setVelocity(dir);
                            });
                } else {
                    nearby.setVelocity(dir);
                }
                pushed++;
            }
            float pitch = level == 3 ? 1.5f : level == 2 ? 1.2f : 0.8f;
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, pitch);
            player.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, player.getLocation(), particles);
            if (pushed > 0) player.sendMessage("§d§lForce Push" + lvlTag + " §7hit §f" + pushed + "§7 player(s)!");
            return;
        }

        // Bed Anchor — right-click to shield your bed from the next hit
        if (BedWarsGame.isBedAnchor(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (game.placeBedAnchor(player)) {
                item.setAmount(item.getAmount() - 1);
                player.sendMessage("§6§lBed Anchor §7applied — next hit on your bed is blocked!");
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
            } else {
                player.sendMessage("§cYour bed is destroyed!");
            }
            return;
        }

        // Lightning Scroll — open target selector GUI
        if (BedWarsGame.isLightningScroll(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            openLightningScrollGui(player, game, item);
            return;
        }

        // Nuclear Bomb — open base selector GUI
        if (BedWarsGame.isNuclearBomb(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            game.openNuclearBombGui(player);
            return;
        }

        // Black Hole — deploy at player's location
        if (BedWarsGame.isBlackHole(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            item.setAmount(item.getAmount() - 1);
            game.activateBlackHole(player.getLocation(), player);
            return;
        }

        // Position Swapper — open target GUI
        if (BedWarsGame.isPositionSwapper(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            game.openSwapperGui(player);
            return;
        }

        // Dagger — right-click to throw as a trident
        if (BedWarsGame.isDagger(item)) {            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (player.getCooldown(Material.IRON_SWORD) > 0) return;
            player.setCooldown(Material.IRON_SWORD, 100); // 5 seconds

            // Spawn a trident as the visual projectile
            org.bukkit.entity.Trident trident = player.getWorld().spawn(
                    player.getEyeLocation(), org.bukkit.entity.Trident.class, t -> {
                        t.setShooter(player);
                        t.setVelocity(player.getLocation().getDirection().multiply(2.5));
                        t.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                        t.setLoyaltyLevel(0); // we handle return ourselves
                        t.setInvulnerable(true);
                        // Tag with owner UUID so we can identify it on hit
                        t.getPersistentDataContainer().set(
                                BedWarsGame.DAGGER_OWNER_KEY,
                                org.bukkit.persistence.PersistentDataType.STRING,
                                player.getUniqueId().toString());
                    });

            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 1.2f);

            // Safety net: return dagger after 3 seconds even if it never hits anything
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!trident.isDead()) {
                    trident.remove();
                    returnDaggerToPlayer(player);
                }
            }, 60L);
            return;
        }

        // Rarity Chest — right-click to open slot machine GUI
        if (BedWarsGame.isRarityChest(item)) {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            game.openChestGui(player, item);
            return;
        }

        // Quick Crafting Table — right-click to open GUI
        if (BedWarsGame.isQuickCraftingTable(item)) {            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) return;
            BedWarsGame.openQctGui(player);
            return;
        }

        // Bed Trap — right-click a bed block to arm it
        if (BedWarsGame.isBedTrap(item)) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            Block clicked = event.getClickedBlock();
            if (clicked == null || !(clicked.getBlockData() instanceof org.bukkit.block.data.type.Bed)) {
                player.sendMessage("§cRight-click a bed to place the trap!");
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            String bedTeam = game.placeBedTrap(player, clicked);
            if (bedTeam == null) {
                player.sendMessage("§cCan't place a trap on a destroyed bed!");
                return;
            }
            item.setAmount(item.getAmount() - 1);
            player.sendMessage("§c§lBed Trap §7armed on §e" + bedTeam + "§7's bed!");
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_ATTACH, 1f, 1f);
            clicked.getWorld().spawnParticle(Particle.SMOKE, clicked.getLocation().add(0.5, 1, 0.5), 10, 0.3, 0.3, 0.3, 0.01);
            return;
        }

        if (!BedWarsGame.isFireball(item)) return;
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
        // Dagger (trident) hit handling
        if (event.getEntity() instanceof org.bukkit.entity.Trident trident) {
            String ownerUuid = trident.getPersistentDataContainer().get(
                    BedWarsGame.DAGGER_OWNER_KEY, org.bukkit.persistence.PersistentDataType.STRING);
            if (ownerUuid == null) return;

            BedWarsGame game = getGameInWorld(trident.getWorld());
            if (game == null) return;

            Player owner = Bukkit.getPlayer(UUID.fromString(ownerUuid));

            // Deal damage to hit player
            if (event.getHitEntity() instanceof Player victim && !victim.equals(owner)) {
                String ownerTeam = owner != null ? game.getPlayerTeamName(owner) : null;
                String victimTeam = game.getPlayerTeamName(victim);
                // Don't damage teammates or spectators
                if (ownerTeam == null || !ownerTeam.equals(victimTeam) && !game.isSpectator(victim.getUniqueId())) {
                    victim.damage(8.0, owner); // ~4 hearts
                    victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_TRIDENT_HIT, 1f, 1f);
                }
            }

            trident.remove();
            if (owner != null) returnDaggerToPlayer(owner);
            return;
        }

        // Play explosion effect when fireball hits something
        if (!(event.getEntity() instanceof Fireball fireball)) return;

        BedWarsGame game = getGameInWorld(event.getEntity().getWorld());
        if (game == null) return;

        Location hitLoc = event.getEntity().getLocation();
        hitLoc.getWorld().playSound(hitLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        hitLoc.getWorld().spawnParticle(Particle.EXPLOSION, hitLoc, 1);
    }

    private void returnDaggerToPlayer(Player player) {
        if (!player.isOnline()) return;
        // Check they don't already have it back (safety net + hit both firing)
        for (ItemStack s : player.getInventory().getContents()) {
            if (BedWarsGame.isDagger(s)) return;
        }
        player.getInventory().addItem(BedWarsGame.makeDagger());
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1.2f);
        player.sendMessage("§5§lDagger §7returned!");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBedEnter(PlayerBedEnterEvent event) {        // Prevent players from sleeping in beds during a game — beds are game objects, not sleep points
        if (getActiveGame(event.getPlayer()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Villager)) return;
        Player player = event.getPlayer();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        if (!game.isShopVillager(event.getRightClicked())) return;
        event.setCancelled(true); // prevent vanilla trade GUI
        if (game.isSpectator(player.getUniqueId())) return;
        game.openShopGui(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;

        event.setDeathMessage(null);
        event.getDrops().clear();
        event.setDroppedExp(0);

        // Check for Totem of Keep Inventory before the inventory is wiped.
        // Scan main inventory first, then offhand. Only one totem is consumed.
        org.bukkit.inventory.ItemStack[] savedContents = null;
        org.bukkit.inventory.ItemStack[] savedArmor    = null;
        org.bukkit.inventory.ItemStack   savedOffhand  = null;

        int totemMainSlot = -1;
        boolean totemInOffhand = false;

        org.bukkit.inventory.ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (BedWarsGame.isTotem(contents[i])) { totemMainSlot = i; break; }
        }
        if (totemMainSlot == -1 && BedWarsGame.isTotem(player.getInventory().getItemInOffHand())) {
            totemInOffhand = true;
        }

        boolean hasTotem = totemMainSlot != -1 || totemInOffhand;
        if (hasTotem) {
            // Deep-copy the full inventory snapshot
            savedContents = new org.bukkit.inventory.ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) {
                savedContents[i] = contents[i] != null ? contents[i].clone() : null;
            }
            savedArmor = new org.bukkit.inventory.ItemStack[player.getInventory().getArmorContents().length];
            org.bukkit.inventory.ItemStack[] armor = player.getInventory().getArmorContents();
            for (int i = 0; i < armor.length; i++) {
                savedArmor[i] = armor[i] != null ? armor[i].clone() : null;
            }
            savedOffhand = player.getInventory().getItemInOffHand().clone();

            // Consume exactly one totem from the snapshot
            if (totemMainSlot != -1) {
                savedContents[totemMainSlot] = null;
            } else {
                savedOffhand = null;
            }
        }

        final boolean keepInv = hasTotem;
        final org.bukkit.inventory.ItemStack[] fContents  = savedContents;
        final org.bukkit.inventory.ItemStack[] fArmor     = savedArmor;
        final org.bukkit.inventory.ItemStack   fOffhand   = savedOffhand;

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
                        if (keepInv) {
                            game.markTotemUsed(player.getUniqueId());
                            player.getInventory().setContents(fContents);
                            player.getInventory().setArmorContents(fArmor);
                            player.getInventory().setItemInOffHand(fOffhand != null ? fOffhand : new org.bukkit.inventory.ItemStack(Material.AIR));
                            player.sendMessage("§6§lTotem of Keep Inventory §7consumed — your items were saved!");
                            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1.2f);
                        }
                        game.handlePlayerDeath(player);
                    }
                }, 2L);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityResurrect(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (getActiveGame(player) == null) return;
        // Cancel vanilla totem-of-undying resurrection entirely — we handle death ourselves.
        // This also prevents a vanilla totem in offhand from blocking the PlayerDeathEvent.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerAnimation(org.bukkit.event.player.PlayerAnimationEvent event) {
        if (event.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Player attacker = event.getPlayer();
        BedWarsGame game = getActiveGame(attacker);
        if (game == null || game.isSpectator(attacker.getUniqueId())) return;

        // Check if the player is facing a bot within melee reach (3.5 blocks)
        // and has a weapon in hand (not just walking around)
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        String handName = hand.getType().name();
        boolean hasWeapon = handName.endsWith("_SWORD") || handName.endsWith("_AXE")
                || hand.getType() == Material.AIR; // fist also counts

        if (!hasWeapon) return;

        org.bukkit.util.Vector facing = attacker.getLocation().getDirection().normalize();
        org.bukkit.Location eye = attacker.getEyeLocation();

        for (UUID botId : KokoPixel.getInstance().getBotManager().getBotsInGame(game.getGameId())) {
            KokoPixel.getInstance().getBotManager().getHandle(botId).ifPresent(h -> {
                // Bot eye position (1.62 above feet)
                org.bukkit.Location botEye = h.getLocation().clone().add(0, 1.62, 0);
                double dist = eye.distance(botEye);
                if (dist > 3.5) return;

                // Check if player is roughly facing the bot (within 45°)
                org.bukkit.util.Vector toBot = botEye.toVector()
                        .subtract(eye.toVector()).normalize();
                if (facing.dot(toBot) < Math.cos(Math.toRadians(45))) return;

                // Strip attacker's spawn immunity
                game.removeSpawnImmunity(attacker);

                // Apply damage — base 5 HP (2.5 hearts) for fist/sword, scaled by weapon
                int dmg = handName.endsWith("_SWORD") ? 7
                        : handName.endsWith("_AXE") ? 9
                        : 3; // fist
                applyBotDamage(botId, game, dmg, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        // Route damage on bot shadow ArmorStands to the bot death system
        if (event.getEntity() instanceof org.bukkit.entity.ArmorStand stand
                && stand.getScoreboardTags().contains("kp_bot_shadow")) {
            event.setCancelled(true); // absorb — we handle HP manually
            BedWarsGame game = getGameInWorld(stand.getWorld());
            if (game == null) return;

            UUID botId = KokoPixel.getInstance().getBotManager()
                    .getBotIdForStand(stand.getUniqueId());
            if (botId == null) return;

            applyBotDamage(botId, game, (int) Math.max(1, event.getFinalDamage()), event.getCause());
            return;
        }

        // Also handle the case where a player tries to hit the fake player VISUAL —
        // the visual is a packet-only entity so it gets no events, but the player's
        // attack still fires EntityDamageByEntityEvent on whatever real entity is nearby.
        // If an attacker hits nothing but is close to a bot, apply melee damage manually.
        if (event instanceof EntityDamageByEntityEvent edbeCheck
                && edbeCheck.getDamager() instanceof Player attacker) {
            BedWarsGame game2 = getActiveGame(attacker);
            if (game2 != null && !game2.isSpectator(attacker.getUniqueId())) {
                // Check if attacker is close to a bot stand (within 4 blocks, in combat reach)
                for (UUID botId : KokoPixel.getInstance().getBotManager()
                        .getBotsInGame(game2.getGameId())) {
                    KokoPixel.getInstance().getBotManager().getHandle(botId).ifPresent(h -> {
                        double dist = h.getLocation().distance(attacker.getLocation());
                        // Only intercept if the attacked entity is NOT the stand itself
                        // (that case is already handled above) and attacker is in melee range
                        if (dist < 4.0 && !(event.getEntity() instanceof org.bukkit.entity.ArmorStand)) {
                            // Don't cancel — just also damage the bot
                            applyBotDamage(botId, game2,
                                    (int) Math.max(1, event.getFinalDamage()),
                                    EntityDamageEvent.DamageCause.ENTITY_ATTACK);
                        }
                    });
                }
            }
        }

        if (event.getEntity() instanceof Player victim) {
            BedWarsGame game = getActiveGame(victim);
            if (game == null) return;

            // Block damage if victim has spawn immunity
            if (game.hasSpawnImmunity(victim.getUniqueId())) {
                event.setCancelled(true);
                return;
            }

            // Instantly kill players who fall into the void
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                event.setCancelled(true);
                victim.setHealth(0);
                return;
            }

            // Prevent spectators from dealing damage
            if (event instanceof EntityDamageByEntityEvent edbe && edbe.getDamager() instanceof Player damager) {
                if (game.isSpectator(damager.getUniqueId())) {
                    event.setCancelled(true);
                    return;
                }
                // Strip spawn immunity from the attacker when they deal damage
                BedWarsGame attackerGame = getActiveGame(damager);
                if (attackerGame != null) attackerGame.removeSpawnImmunity(damager);
            }
            
            // Prevent spectators from taking damage (they should be invulnerable already)
            if (game.isSpectator(victim.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            BedWarsGame game = getActiveGame(player);
            if (game == null) return;
            
            // Prevent spectators from launching projectiles
            if (game.isSpectator(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            BedWarsGame game = getActiveGame(player);
            if (game == null) return;
            
            // Prevent spectators from picking up items
            if (game.isSpectator(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        BedWarsGame game = getActiveGame(event.getPlayer());
        if (game == null) return;
        
        // Prevent spectators from dropping items
        if (game.isSpectator(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }


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

    private void applyBotDamage(UUID botId, BedWarsGame game, int dmg,
                                 EntityDamageEvent.DamageCause cause) {
        boolean instant = cause == EntityDamageEvent.DamageCause.LIGHTNING
                || cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (instant) {
            KokoPixel.getInstance().getBotManager().notifyBotDeath(botId, game);
        } else {
            String key = "bot_hp_" + botId;
            int cur = (int) game.getData().getOrDefault(key, 20);
            int newHp = cur - dmg;
            if (newHp <= 0) {
                game.getData().remove(key);
                KokoPixel.getInstance().getBotManager().notifyBotDeath(botId, game);
            } else {
                game.getData().put(key, newHp);
            }
        }
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

    // -------------------------------------------------------------------------
    // Lightning Scroll GUI
    // -------------------------------------------------------------------------

    // Title used to identify the GUI so we can intercept clicks
    private static final String LIGHTNING_GUI_TITLE = "§e§lLightning Scroll — Choose Target";

    private void openLightningScrollGui(Player user, BedWarsGame game, ItemStack scroll) {
        String userTeam = game.getPlayerTeamName(user);

        // Collect valid targets: alive, not spectator, not on same team
        java.util.List<Player> targets = new java.util.ArrayList<>();
        for (cc.kokodev.kokopixel.api.game.GamePlayer gp : game.getPlayers()) {
            Player p = gp.getPlayer();
            if (p.equals(user)) continue;
            if (game.isSpectator(p.getUniqueId())) continue;
            String t = game.getPlayerTeamName(p);
            if (userTeam != null && userTeam.equals(t)) continue;
            targets.add(p);
        }

        if (targets.isEmpty()) {
            user.sendMessage("§cNo valid targets!");
            return;
        }

        // Size must be a multiple of 9, large enough for all targets
        int size = (int) Math.ceil(targets.size() / 9.0) * 9;
        size = Math.max(9, Math.min(size, 54));

        org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, size,
                net.kyori.adventure.text.Component.text(LIGHTNING_GUI_TITLE));

        for (Player target : targets) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.displayName(net.kyori.adventure.text.Component.text("§c" + target.getName())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text("§7Click to strike with lightning!")
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            // Store target UUID in PDC so we can read it on click
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "lightning_target"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    target.getUniqueId().toString());
            head.setItemMeta(meta);
            gui.addItem(head);
        }

        user.openInventory(gui);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Rarity Chest slot-machine GUI
        {
            org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
            ItemStack stateItem = top.getSize() == 27 ? top.getItem(0) : null;
            if (stateItem != null && stateItem.getType() == Material.BARRIER) {
                org.bukkit.inventory.meta.ItemMeta sm = stateItem.getItemMeta();
                String rarityName = sm != null ? sm.getPersistentDataContainer().get(
                        new org.bukkit.NamespacedKey("obbedwars", "chest_rarity"),
                        org.bukkit.persistence.PersistentDataType.STRING) : null;
                if (rarityName != null) {
                    event.setCancelled(true);
                    int rawSlot = event.getRawSlot();
                    if (rawSlot < 27) { // only top inventory
                        BedWarsGame game = getActiveGame(player);
                        if (game != null) game.handleChestGuiClick(player, top, rawSlot, rarityName);
                    }
                    return;
                }
            }
        }

        // Shop GUI
        if (event.getView().title().equals(net.kyori.adventure.text.Component.text("Item Shop")
                .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))) {            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            org.bukkit.inventory.meta.ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;
            Integer slot = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("obbedwars", "shop_slot"),
                    org.bukkit.persistence.PersistentDataType.INTEGER);
            if (slot == null) return;
            BedWarsGame game = getActiveGame(player);
            if (game != null) game.handleShopClick(player, slot);
            return;
        }

        // Quick Crafting Table GUI
        if (event.getView().title().equals(net.kyori.adventure.text.Component.text(BedWarsGame.QCT_GUI_TITLE))) {
            int slot = event.getRawSlot();

            // Allow placing/taking from template slot (slot 11) only
            if (slot == BedWarsGame.QCT_TEMPLATE_SLOT) {
                // Let the click happen naturally so the player can place/take the template,
                // then refresh the craft button on the next tick
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    org.bukkit.inventory.Inventory inv = player.getOpenInventory().getTopInventory();
                    if (!player.getOpenInventory().title()
                            .equals(net.kyori.adventure.text.Component.text(BedWarsGame.QCT_GUI_TITLE))) return;
                    ItemStack tmplItem = inv.getItem(BedWarsGame.QCT_TEMPLATE_SLOT);
                    String tmplId = BedWarsGame.getTemplateId(tmplItem);
                    BedWarsGame.CraftTemplate tmpl = tmplId == null ? null :
                            BedWarsGame.TEMPLATES.stream().filter(t -> t.id().equals(tmplId)).findFirst().orElse(null);
                    inv.setItem(BedWarsGame.QCT_CRAFT_SLOT, BedWarsGame.buildCraftButton(tmpl));
                });
                return; // don't cancel — let the item move
            }

            // Craft button clicked
            if (slot == BedWarsGame.QCT_CRAFT_SLOT) {
                event.setCancelled(true);
                org.bukkit.inventory.Inventory inv = event.getView().getTopInventory();
                ItemStack tmplItem = inv.getItem(BedWarsGame.QCT_TEMPLATE_SLOT);
                String tmplId = BedWarsGame.getTemplateId(tmplItem);
                if (tmplId == null) {
                    player.sendMessage("§cPlace a template first!");
                    return;
                }
                BedWarsGame.CraftTemplate tmpl = BedWarsGame.TEMPLATES.stream()
                        .filter(t -> t.id().equals(tmplId)).findFirst().orElse(null);
                if (tmpl == null) return;

                if (!tmpl.canAfford().test(player)) {
                    player.sendMessage("§cNot enough materials! Need: " + tmpl.costDesc());
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }

                // Deduct cost, give items, consume template
                tmpl.deductCost().accept(player);
                for (ItemStack result : tmpl.result().get()) player.getInventory().addItem(result);
                inv.setItem(BedWarsGame.QCT_TEMPLATE_SLOT, null);
                inv.setItem(BedWarsGame.QCT_CRAFT_SLOT, BedWarsGame.buildCraftButton(null));
                player.sendMessage("§a§lCrafted: §r" + tmpl.displayName() + "§a!");
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.5f);
                return;
            }

            // Block all other slots except player inventory (bottom half)
            if (slot < 27) event.setCancelled(true);
            return;
        }

        // Always cancel clicks inside our Lightning GUI
        if (event.getView().title().equals(net.kyori.adventure.text.Component.text(LIGHTNING_GUI_TITLE))) {            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

            org.bukkit.inventory.meta.ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;
            String uuidStr = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("obbedwars", "lightning_target"),
                    org.bukkit.persistence.PersistentDataType.STRING);
            if (uuidStr == null) return;

            Player target = Bukkit.getPlayer(UUID.fromString(uuidStr));
            // Fallback: look up in the game's player list (covers bots whose CraftPlayer
            // is not in Bukkit's online player list)
            if (target == null) {
                UUID targetUuid = UUID.fromString(uuidStr);
                BedWarsGame curGame = getGameInWorld(player.getWorld());
                if (curGame != null) {
                    target = curGame.getPlayers().stream()
                            .filter(gp -> gp.getUniqueId().equals(targetUuid))
                            .map(cc.kokodev.kokopixel.api.game.GamePlayer::getPlayer)
                            .findFirst().orElse(null);
                }
            }
            player.closeInventory();

            if (target == null) {
                player.sendMessage("§cThat player is no longer available.");
                return;
            }

            // Consume the scroll from the player's hand
            ItemStack scroll = player.getInventory().getItemInMainHand();
            if (BedWarsGame.isLightningScroll(scroll)) {
                scroll.setAmount(scroll.getAmount() - 1);
            } else {
                // Fallback: search inventory
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (BedWarsGame.isLightningScroll(s)) {
                        s.setAmount(s.getAmount() - 1);
                        break;
                    }
                }
            }

            // Strike lightning at target's location
            target.getWorld().strikeLightning(target.getLocation());
            // If target is a bot, the lightning won't trigger PlayerDeathEvent — notify manually
            if (KokoPixel.getInstance().getBotManager().isBot(target.getUniqueId())) {
                BedWarsGame curGame = getGameInWorld(target.getWorld());
                if (curGame != null)
                    KokoPixel.getInstance().getBotManager()
                            .notifyBotDeath(target.getUniqueId(), curGame);
            }
            player.sendMessage("§e§lLightning Scroll §7— struck §c" + target.getName() + "§7!");
            return;
        }

        // Nuclear Bomb GUI
        if (event.getView().title().equals(net.kyori.adventure.text.Component.text(BedWarsGame.NUCLEAR_GUI_TITLE))) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            org.bukkit.inventory.meta.ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;
            String targetTeam = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("obbedwars", "nuke_target"),
                    org.bukkit.persistence.PersistentDataType.STRING);
            if (targetTeam == null) return;
            player.closeInventory();

            BedWarsGame game2 = getActiveGame(player);
            if (game2 == null) return;

            // Consume the bomb
            ItemStack bomb = player.getInventory().getItemInMainHand();
            if (BedWarsGame.isNuclearBomb(bomb)) {
                bomb.setAmount(bomb.getAmount() - 1);
            } else {
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (BedWarsGame.isNuclearBomb(s)) { s.setAmount(s.getAmount() - 1); break; }
                }
            }

            game2.strikeNuclearBomb(targetTeam, player);
            return;
        }

        // Admin Item Browser GUI
        if (event.getView().title().equals(
                net.kyori.adventure.text.Component.text(BedWarsSetupCommand.ADMIN_GUI_TITLE)
                        .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            org.bukkit.inventory.meta.ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;

            // Navigation arrow
            Integer navPage = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("obbedwars", "admin_gui_nav"),
                    org.bukkit.persistence.PersistentDataType.INTEGER);
            if (navPage != null) {
                // Find the setup command instance via the plugin
                BedWarsSetupCommand cmd = (BedWarsSetupCommand) plugin.getCommand("obbedwars").getExecutor();
                if (cmd != null) cmd.openAdminGui(player, navPage);
                return;
            }

            // Item slot — give the item
            Integer idx = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("obbedwars", "admin_gui_idx"),
                    org.bukkit.persistence.PersistentDataType.INTEGER);
            if (idx != null) {
                java.util.List<ItemStack> items = new java.util.ArrayList<>(
                        BedWarsGame.getLootTableItems().values());
                if (idx >= 0 && idx < items.size()) {
                    ItemStack give = items.get(idx).clone();
                    // Strip the admin_gui_idx tag from the given item
                    org.bukkit.inventory.meta.ItemMeta gm = give.getItemMeta();
                    if (gm != null) {
                        gm.getPersistentDataContainer().remove(
                                new org.bukkit.NamespacedKey("obbedwars", "admin_gui_idx"));
                        give.setItemMeta(gm);
                    }
                    player.getInventory().addItem(give);
                    player.sendMessage("§aGave you §e" + (gm != null && give.getItemMeta().hasDisplayName()
                            ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                    .plainText().serialize(give.getItemMeta().displayName())
                            : give.getType().name().toLowerCase()) + "§a.");
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                }
            }
            return;
        }

        // Position Swapper GUI
        if (event.getView().title().equals(net.kyori.adventure.text.Component.text(BedWarsGame.SWAPPER_GUI_TITLE))) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            org.bukkit.inventory.meta.ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;
            String uuidStr = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey("obbedwars", "swap_target"),
                    org.bukkit.persistence.PersistentDataType.STRING);
            if (uuidStr == null) return;
            player.closeInventory();

            Player target = Bukkit.getPlayer(UUID.fromString(uuidStr));
            if (target == null || !target.isOnline()) {
                player.sendMessage("§cThat player is no longer available.");
                return;
            }

            // Consume the swapper
            ItemStack swapper = player.getInventory().getItemInMainHand();
            if (BedWarsGame.isPositionSwapper(swapper)) {
                swapper.setAmount(swapper.getAmount() - 1);
            } else {
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (BedWarsGame.isPositionSwapper(s)) { s.setAmount(s.getAmount() - 1); break; }
                }
            }

            // Swap locations
            Location userLoc  = player.getLocation().clone();
            Location targetLoc = target.getLocation().clone();
            player.teleport(targetLoc);
            target.teleport(userLoc);

            // 3 seconds of invulnerability for both
            player.setInvulnerable(true);
            target.setInvulnerable(true);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) player.setInvulnerable(false);
            }, 60L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (target.isOnline()) target.setInvulnerable(false);
            }, 60L);

            // Visual + sound feedback
            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation(), 30, 0.5, 1, 0.5, 0.2);
            target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation(), 30, 0.5, 1, 0.5, 0.2);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);

            player.sendMessage("§5§lPosition Swapper §7— swapped with §c" + target.getName() + "§7!");
            target.sendMessage("§5§lPosition Swapper §7— §c" + player.getName() + " §7swapped positions with you!");
            return;
        }

        // Original spectator inventory block
        BedWarsGame game = getActiveGame(player);
        if (game == null) return;
        if (game.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}