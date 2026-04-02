package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameState;
import cc.kokodev.kokopixel.api.game.GameTeam;
import cc.kokodev.kokopixel.minigames.GameInstanceImpl;
import cc.kokodev.kokopixel.minigames.GamePlayerImpl;
import cc.kokodev.kokopixel.minigames.Minigame;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * One game instance of OBBedWars.
 *
 * Rules:
 * - Each team (solo player) has a bed. Breaking your own bed gives random loot.
 * - An enemy breaking your bed destroys it permanently.
 * - Die with no bed → spectator.
 * - Last player standing wins.
 * - Diamond generators spawn diamonds periodically.
 */
public class BedWarsGame extends GameInstanceImpl {

    // teamName -> whether the bed still exists
    private final Map<String, Boolean> bedAlive = new LinkedHashMap<>();
    // teamName -> bed location in the cloned world
    private final Map<String, Location> bedLocations = new LinkedHashMap<>();
    // diamond gen locations in the cloned world
    private final List<Location> diamondGens = new ArrayList<>();

    private Scoreboard scoreboard;
    private org.bukkit.scoreboard.Objective objective;

    // Loot table — items dropped when you mine your own bed
    private static final List<ItemStack[]> LOOT_TABLE = List.of(
        new ItemStack[]{ new ItemStack(Material.OAK_LOG, 16) },
        new ItemStack[]{ new ItemStack(Material.OAK_LOG, 8), new ItemStack(Material.COBBLESTONE, 16) },
        new ItemStack[]{ new ItemStack(Material.IRON_INGOT, 4) },
        new ItemStack[]{ new ItemStack(Material.IRON_INGOT, 8) },
        new ItemStack[]{ new ItemStack(Material.GOLDEN_APPLE, 2) },
        new ItemStack[]{ new ItemStack(Material.IRON_SWORD) },
        new ItemStack[]{ new ItemStack(Material.BOW), new ItemStack(Material.ARROW, 16) },
        new ItemStack[]{ new ItemStack(Material.IRON_CHESTPLATE) },
        new ItemStack[]{ new ItemStack(Material.CRAFTING_TABLE) },
        new ItemStack[]{ new ItemStack(Material.OBSIDIAN, 4) },
        new ItemStack[]{ new ItemStack(Material.TNT) },
        new ItemStack[]{ new ItemStack(Material.DIAMOND) },
        new ItemStack[]{ new ItemStack(Material.DIAMOND, 2) },
        new ItemStack[]{ new ItemStack(Material.ENDER_PEARL) },
        new ItemStack[]{ makeFireball(1) },
        new ItemStack[]{ makeFireball(3) }
    );

    private static final Random RANDOM = new Random();

    public BedWarsGame(Minigame minigame, World world, JavaPlugin plugin) {
        super(minigame, world, plugin);
    }

    @Override
    protected void onGameStart() {
        BedWarsMinigame bwMinigame = (BedWarsMinigame) minigame;

        // Map bed locations from template world to this cloned world
        for (Map.Entry<String, Location> entry : bwMinigame.getBedLocations().entrySet()) {
            Location templateLoc = entry.getValue();
            Location gameLoc = new Location(world,
                    templateLoc.getX(), templateLoc.getY(), templateLoc.getZ(),
                    templateLoc.getYaw(), templateLoc.getPitch());
            bedLocations.put(entry.getKey(), gameLoc);
        }

        // Map diamond gen locations
        for (Location templateLoc : bwMinigame.getDiamondGens()) {
            diamondGens.add(new Location(world,
                    templateLoc.getX(), templateLoc.getY(), templateLoc.getZ()));
        }

        // Mark all configured teams as having a bed
        // Teams with no player assigned get marked as eliminated immediately
        for (GameTeam team : getTeams()) {
            boolean hasBed = bedLocations.containsKey(team.getName().toLowerCase());
            boolean hasPlayer = !team.getMembers().isEmpty();
            bedAlive.put(team.getName().toLowerCase(), hasBed && hasPlayer);
            if (!hasPlayer || !hasBed) team.setEliminated(true);
        }

        // Teleport each player to their team spawn
        for (GamePlayer gp : getPlayers()) {
            gp.getTeam().ifPresent(team -> {
                teleportToTeamSpawn(gp.getPlayer(), team.getName());
                gp.getPlayer().setGameMode(GameMode.SURVIVAL);
                gp.getPlayer().getInventory().clear();
            });
        }

        // Start diamond generators
        startDiamondGens();

        // Setup scoreboard
        setupScoreboard();

        broadcast("§aBeds are placed! Mine §eyour own bed §afor loot. Protect it from enemies!");
    }

    @Override
    protected void onGameEnd(List<UUID> winners) {
        // Clean up scoreboard
        for (GamePlayer gp : getPlayers()) {
            gp.getPlayer().setScoreboard(
                    Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    @Override
    protected void onPlayerJoin(GamePlayerImpl player) {
        // Give starting kit
        player.getPlayer().getInventory().addItem(
                new ItemStack(Material.WOODEN_PICKAXE),
                new ItemStack(Material.WOODEN_SWORD),
                new ItemStack(Material.OAK_LOG, 8),
                new ItemStack(Material.BREAD, 4)
        );
    }

    @Override
    protected void onPlayerLeave(GamePlayerImpl player) {
        // Only check win condition if the leaving player was still active (not already eliminated)
        String teamName = getPlayerTeamName(player.getPlayer());
        boolean wasEliminated = teamName != null && getTeam(teamName)
                .map(GameTeam::isEliminated).orElse(false);
        if (!wasEliminated) {
            // Active player disconnected — treat as elimination
            if (teamName != null) {
                bedAlive.put(teamName, false);
                getTeam(teamName).ifPresent(t -> t.setEliminated(true));
                broadcast("§c" + player.getName() + " §7disconnected and was eliminated!");
                updateScoreboard();
            }
            checkWinCondition();
        }
    }

    // -------------------------------------------------------------------------
    // Bed break handling — called from BedWarsListener
    // -------------------------------------------------------------------------

    /**
     * Called when a player breaks a bed block.
     * @param breaker the player who broke it
     * @param bedBlock the block that was broken
     * @return true if we handled it (cancel the vanilla break)
     */
    public boolean handleBedBreak(Player breaker, Block bedBlock) {
        if (getState() != GameState.ACTIVE) return false;

        // Find which team this bed belongs to
        String bedTeam = getBedTeamAt(bedBlock.getLocation());
        if (bedTeam == null) return false;

        String breakerTeam = getPlayerTeamName(breaker);

        if (bedTeam.equals(breakerTeam)) {
            // Breaking your own bed — give loot, keep bed intact
            ItemStack[] loot = LOOT_TABLE.get(RANDOM.nextInt(LOOT_TABLE.size()));
            for (ItemStack item : loot) breaker.getInventory().addItem(item);
            breaker.sendMessage("§a✦ Bed loot: §e" + describeItems(loot));
            breaker.playSound(breaker.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return true; // cancel the break — bed stays
        } else {
            // Enemy breaking your bed — destroy it
            destroyBed(bedTeam, breaker);
            return false; // let the block break normally
        }
    }

    private void destroyBed(String teamName, Player destroyer) {
        bedAlive.put(teamName.toLowerCase(), false);

        // Find the team's player and notify them
        getTeam(teamName).ifPresent(team -> {
            String destroyerName = destroyer != null ? destroyer.getName() : "unknown";
            for (GamePlayer gp : team.getMembers()) {
                gp.getPlayer().sendMessage("§c§lYour bed was destroyed by §e" + destroyerName + "§c!");
                gp.getPlayer().showTitle(
                        net.kyori.adventure.title.Title.title(
                                net.kyori.adventure.text.Component.text("§c§lBED DESTROYED!"),
                                net.kyori.adventure.text.Component.text("§7You will not respawn!"),
                                net.kyori.adventure.title.Title.Times.times(
                                        java.time.Duration.ofMillis(250),
                                        java.time.Duration.ofMillis(3000),
                                        java.time.Duration.ofMillis(500))));
            }
            if (destroyer != null) {
                destroyer.sendMessage("§a✦ Destroyed §e" + teamName + "§a's bed!");
                destroyer.playSound(destroyer.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
            }
        });

        broadcast("§c§l" + teamName.toUpperCase() + "'s bed was destroyed by §e"
                + (destroyer != null ? destroyer.getName() : "the void") + "§c!");
        updateScoreboard();
    }

    // -------------------------------------------------------------------------
    // Death handling — called from BedWarsListener
    // -------------------------------------------------------------------------

    public void handlePlayerDeath(Player player) {
        if (getState() != GameState.ACTIVE) return;
        String teamName = getPlayerTeamName(player);

        plugin.getLogger().info("[BedWars] Death: " + player.getName()
                + " team=" + teamName
                + " bedAlive=" + bedAlive);

        if (teamName == null) {
            plugin.getLogger().warning("[BedWars] No team found for " + player.getName());
            return;
        }

        boolean hasBed = bedAlive.getOrDefault(teamName.toLowerCase(), false);

        if (hasBed) {
            // Has bed — 5-second countdown then respawn at safest spot near bed
            // Use KokoPixel's spectator mode (survival + fly, not vanilla spectator)
            // so the player stays visible in the world and can't interact
            enableInWorldSpectator(player);
            startRespawnCountdown(player, teamName, 5);
        } else {
            // No bed — eliminated, spectate in-world using KokoPixel spectator mode
            getPlayer(player.getUniqueId()).ifPresent(gp -> gp.addDeath());
            getTeam(teamName).ifPresent(t -> t.setEliminated(true));
            enableInWorldSpectator(player);
            player.sendMessage("§c§lYou have been eliminated! Spectating the rest of the game.");
            broadcast("§c§l" + player.getName() + " §7(" + teamName + ") §cwas eliminated!");
            updateScoreboard();
            checkWinCondition();
        }
    }

    private void startRespawnCountdown(Player player, String teamName, int seconds) {
        // Show countdown title
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("§c§l" + seconds),
                net.kyori.adventure.text.Component.text("§7Respawning..."),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ZERO,
                        java.time.Duration.ofMillis(1100),
                        java.time.Duration.ZERO)));

        if (seconds <= 0) {
            // Respawn now — disable spectator mode and teleport to safe spot
            disableInWorldSpectator(player);
            Location respawn = findSafeRespawnNearBed(teamName);
            player.teleport(respawn);
            player.setHealth(20);
            player.setFoodLevel(20);
            player.sendMessage("§aRespawned!");
            return;
        }

        runTaskLater(() -> {
            if (!player.isOnline() || getState() != GameState.ACTIVE) return;
            startRespawnCountdown(player, teamName, seconds - 1);
        }, 20L);
    }

    /**
     * Finds the safest block to respawn on near the team's bed.
     * Searches outward from the bed location for a solid floor with 2 air blocks above.
     * Never returns a location on top of a diamond generator.
     */
    private Location findSafeRespawnNearBed(String teamName) {
        Location bedLoc = bedLocations.get(teamName.toLowerCase());
        if (bedLoc == null) {
            // Fallback to team spawn
            List<Location> spawns = minigame.getTeamSpawnPoints(teamName);
            if (!spawns.isEmpty()) return spawns.get(0).clone().add(0, 0.1, 0);
            return world.getSpawnLocation();
        }

        // Search in expanding rings around the bed
        for (int radius = 0; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue; // only outer ring
                    for (int dy = 2; dy >= -2; dy--) {
                        Location candidate = bedLoc.clone().add(dx, dy, dz);
                        if (isSafeRespawn(candidate)) {
                            return candidate.add(0.5, 1, 0.5); // center of block, stand on top
                        }
                    }
                }
            }
        }

        // Absolute fallback
        return bedLoc.clone().add(0.5, 2, 0.5);
    }

    /**
     * Puts a player into in-world spectator mode:
     * survival gamemode + flying + invulnerable + invisible + no collision.
     * Does NOT give spectator items or teleport — player stays where they are.
     */
    private void enableInWorldSpectator(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setCanPickupItems(false);
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE, 0, false, false, false));
    }

    /** Reverses enableInWorldSpectator — restores normal survival state. */
    private void disableInWorldSpectator(Player player) {
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setCanPickupItems(true);
        player.setGameMode(GameMode.SURVIVAL);
    }

    private boolean isSafeRespawn(Location floor) {
        Block floorBlock = world.getBlockAt(floor);
        Block feet = world.getBlockAt(floor.clone().add(0, 1, 0));
        Block head = world.getBlockAt(floor.clone().add(0, 2, 0));

        // Floor must be solid, feet and head must be passable
        if (!floorBlock.getType().isSolid()) return false;
        if (!feet.isPassable()) return false;
        if (!head.isPassable()) return false;

        // Don't respawn on top of a diamond generator
        Location floorLoc = floorBlock.getLocation();
        for (Location gen : diamondGens) {
            if (Math.abs(gen.getBlockX() - floorLoc.getBlockX()) <= 1
                    && Math.abs(gen.getBlockZ() - floorLoc.getBlockZ()) <= 1
                    && Math.abs(gen.getBlockY() - floorLoc.getBlockY()) <= 2) {
                return false;
            }
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Win condition
    // -------------------------------------------------------------------------

    private void checkWinCondition() {
        // A team is still in the game if: not eliminated AND has at least one member
        // who is alive (not in spectator due to elimination)
        List<GameTeam> alive = getTeams().stream()
                .filter(t -> !t.isEliminated() && !t.getMembers().isEmpty())
                .toList();

        if (alive.size() == 1) {
            GamePlayer winner = alive.get(0).getMembers().get(0);
            end(List.of(winner.getUniqueId()));
        } else if (alive.isEmpty()) {
            end();
        }
    }

    // -------------------------------------------------------------------------
    // Diamond generators
    // -------------------------------------------------------------------------

    private void startDiamondGens() {
        if (diamondGens.isEmpty()) return;
        runTaskTimer(() -> {
            for (Location loc : diamondGens) {
                world.dropItemNaturally(loc, new ItemStack(Material.DIAMOND));
                world.spawnParticle(Particle.ITEM, loc, 5, 0.3, 0.3, 0.3, 0,
                        new ItemStack(Material.DIAMOND));
            }
        }, 200L, 200L); // every 10 seconds
    }

    // -------------------------------------------------------------------------
    // Scoreboard
    // -------------------------------------------------------------------------

    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = scoreboard.registerNewObjective("bedwars", Criteria.DUMMY,
                net.kyori.adventure.text.Component.text("§6§lBEDWARS"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        updateScoreboard();
        for (GamePlayer gp : getPlayers()) gp.getPlayer().setScoreboard(scoreboard);
    }

    private void updateScoreboard() {
        if (objective == null) return;
        // Reset all scores
        for (String entry : new HashSet<>(scoreboard.getEntries())) scoreboard.resetScores(entry);

        List<GameTeam> allTeams = getTeams();
        int line = allTeams.size();

        for (GameTeam team : allTeams) {
            ChatColor color = team.getColor();
            String status;
            if (team.isEliminated() || team.getMembers().isEmpty()) {
                status = "§c✗";
            } else if (bedAlive.getOrDefault(team.getName(), false)) {
                status = "§a✔";
            } else {
                // Bed gone but player alive — show player count
                long alive = team.getMembers().stream().filter(GamePlayer::isAlive).count();
                status = "§e" + alive;
            }

            String entry = color + "§l" + team.getName().toUpperCase() + " " + status;
            // Pad to make unique if needed
            while (scoreboard.getEntries().contains(entry)) entry += " ";
            objective.getScore(entry).setScore(line--);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String getBedTeamAt(Location loc) {
        for (Map.Entry<String, Location> entry : bedLocations.entrySet()) {
            Location bedLoc = entry.getValue();
            // Check both halves of the bed (head and foot are adjacent)
            if (isSameBed(loc, bedLoc)) return entry.getKey();
        }
        return null;
    }

    private boolean isSameBed(Location a, Location b) {
        // Same block
        if (a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()) return true;
        // Adjacent block (other half of bed)
        Block block = a.getBlock();
        if (block.getBlockData() instanceof Bed bed) {
            BlockFace facing = bed.getFacing();
            if (bed.getPart() == Bed.Part.FOOT) {
                Location head = a.clone().add(facing.getModX(), 0, facing.getModZ());
                return head.getBlockX() == b.getBlockX() && head.getBlockY() == b.getBlockY()
                        && head.getBlockZ() == b.getBlockZ();
            } else {
                Location foot = a.clone().subtract(facing.getModX(), 0, facing.getModZ());
                return foot.getBlockX() == b.getBlockX() && foot.getBlockY() == b.getBlockY()
                        && foot.getBlockZ() == b.getBlockZ();
            }
        }
        return false;
    }

    public String getPlayerTeamName(Player player) {
        return getPlayerTeam(player.getUniqueId())
                .map(GameTeam::getName)
                .orElse(null);
    }

    /** Creates a fire charge item tagged as a throwable fireball. */
    public static ItemStack makeFireball(int amount) {
        ItemStack item = new ItemStack(Material.FIRE_CHARGE, amount);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§c§lFireball")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to throw")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        // Tag it so we can identify it in the interact event
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "fireball"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isFireball(ItemStack item) {
        if (item == null || item.getType() != Material.FIRE_CHARGE) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "fireball"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    private String describeItems(ItemStack[] items) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item.getAmount()).append("x ").append(
                    item.getType().name().toLowerCase().replace('_', ' '));
        }
        return sb.toString();
    }

    public Map<String, Location> getBedLocations() { return bedLocations; }
    public Map<String, Boolean> getBedAlive() { return bedAlive; }
}
