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
import org.jetbrains.annotations.Nullable;

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

    private static final int BED_MAX_HEALTH = 3;
    private static final int HEALTH_BAR_SEGMENTS = 30;

    // teamName -> remaining bed health (0 = destroyed)
    private final Map<String, Integer> bedHealth = new LinkedHashMap<>();
    // teamName -> bed location in the cloned world
    private final Map<String, Location> bedLocations = new LinkedHashMap<>();
    // teamName -> health bar armor stand entity
    private final Map<String, org.bukkit.entity.ArmorStand> bedArmorStands = new LinkedHashMap<>();
    // diamond gen locations in the cloned world
    private final List<Location> diamondGens = new ArrayList<>();
    // teamName -> number of active Bed Anchors stacked on that team's bed
    private final Map<String, Integer> bedAnchors = new HashMap<>();
    // teamName -> UUID of the player who placed a trap on that bed (null = no trap)
    private final Map<String, UUID> bedTraps = new HashMap<>();
    // players who consumed a totem this death — allows respawn even without a bed
    private final Set<UUID> totemRespawnOverride = new HashSet<>();
    // players currently in spectator mode (death countdown or eliminated)
    private final Set<UUID> spectatorPlayers = new HashSet<>();

    // players with spawn immunity — UUID -> expiry System.currentTimeMillis()
    private final Map<UUID, Long> spawnImmune = new HashMap<>();

    private static final long SPAWN_IMMUNITY_MS = 3000L; // 3 seconds

    /** Grant 3-second spawn immunity to a player. */
    public void grantSpawnImmunity(Player player) {
        spawnImmune.put(player.getUniqueId(), System.currentTimeMillis() + SPAWN_IMMUNITY_MS);
        // Bots have no connection — skip the potion effect packet
        if (cc.kokodev.kokopixel.KokoPixel.getInstance().getBotManager().isBot(player.getUniqueId())) return;
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.RESISTANCE, 60, 4, false, false, false));
    }

    /** Remove spawn immunity (e.g. when the player attacks). */
    public void removeSpawnImmunity(Player player) {
        if (spawnImmune.remove(player.getUniqueId()) != null) {
            if (!cc.kokodev.kokopixel.KokoPixel.getInstance().getBotManager().isBot(player.getUniqueId()))
                player.removePotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE);
        }
    }

    /** True if the player currently has active spawn immunity. */
    public boolean hasSpawnImmunity(UUID playerId) {
        Long expiry = spawnImmune.get(playerId);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) { spawnImmune.remove(playerId); return false; }
        return true;
    }

    /** Called by the listener when a totem is consumed on death, before handlePlayerDeath. */
    public void markTotemUsed(UUID playerId) { totemRespawnOverride.add(playerId); }
    // shop NPC villager entities spawned for this game instance
    private final List<org.bukkit.entity.Villager> shopVillagers = new ArrayList<>();

    private Scoreboard scoreboard;
    private org.bukkit.scoreboard.Objective objective;

    // Each loot entry carries its rarity weight for weighted random selection
    private record LootEntry(ItemStack[] items, int weight) {}

    // CraftTemplate must be declared before LOOT_TABLE because makeTemplate() references TEMPLATES
    public record CraftTemplate(String id, String displayName, Rarity rarity,
                                String costDesc, java.util.function.Supplier<ItemStack[]> result,
                                java.util.function.Predicate<Player> canAfford,
                                java.util.function.Consumer<Player> deductCost) {}

    public static final java.util.List<CraftTemplate> TEMPLATES = buildTemplates();

    private static java.util.List<CraftTemplate> buildTemplates() {
        java.util.List<CraftTemplate> t = new java.util.ArrayList<>();

        // Full Iron Armor — 24 iron ingots
        t.add(new CraftTemplate(
                "iron_armor", "§7Full Iron Armor", Rarity.UNCOMMON,
                "§724 Iron Ingots",
                () -> new ItemStack[]{
                        new ItemStack(Material.IRON_HELMET),
                        new ItemStack(Material.IRON_CHESTPLATE),
                        new ItemStack(Material.IRON_LEGGINGS),
                        new ItemStack(Material.IRON_BOOTS)},
                p -> countMaterial(p, Material.IRON_INGOT) >= 24,
                p -> removeMaterial(p, Material.IRON_INGOT, 24)));

        // Full Diamond Armor — 24 diamonds
        t.add(new CraftTemplate(
                "diamond_armor", "§bFull Diamond Armor", Rarity.EPIC,
                "§724 Diamonds",
                () -> new ItemStack[]{
                        new ItemStack(Material.DIAMOND_HELMET),
                        new ItemStack(Material.DIAMOND_CHESTPLATE),
                        new ItemStack(Material.DIAMOND_LEGGINGS),
                        new ItemStack(Material.DIAMOND_BOOTS)},
                p -> countMaterial(p, Material.DIAMOND) >= 24,
                p -> removeMaterial(p, Material.DIAMOND, 24)));

        // Full Diamond Tools — 8 diamonds
        t.add(new CraftTemplate(
                "diamond_tools", "§bFull Diamond Tools", Rarity.RARE,
                "§78 Diamonds",
                () -> new ItemStack[]{
                        new ItemStack(Material.DIAMOND_SWORD),
                        new ItemStack(Material.DIAMOND_PICKAXE),
                        new ItemStack(Material.DIAMOND_AXE)},
                p -> countMaterial(p, Material.DIAMOND) >= 8,
                p -> removeMaterial(p, Material.DIAMOND, 8)));

        // Force Core Push Upgrade — 6 diamonds + existing level 1 Force Core
        t.add(new CraftTemplate(
                "force_core_push_upgrade", "§5Force Core Push II", Rarity.LEGENDARY,
                "§76 Diamonds §8+ §dForce Core Push (Lv.1)",
                () -> new ItemStack[]{ makeForceCorePush(2) },
                p -> countMaterial(p, Material.DIAMOND) >= 6 && findForceCorePushLv1(p) != -1,
                p -> {
                    removeMaterial(p, Material.DIAMOND, 6);
                    int slot = findForceCorePushLv1(p);
                    if (slot != -1) p.getInventory().setItem(slot, null);
                }));

        // Force Core Push Upgrade III — 12 diamonds + existing level 2 Force Core
        t.add(new CraftTemplate(
                "force_core_push_upgrade_3", "§6Force Core Push III", Rarity.LEGENDARY,
                "§712 Diamonds §8+ §5Force Core Push II (Lv.2)",
                () -> new ItemStack[]{ makeForceCorePush(3) },
                p -> countMaterial(p, Material.DIAMOND) >= 12 && findForceCorePushLv2(p) != -1,
                p -> {
                    removeMaterial(p, Material.DIAMOND, 12);
                    int slot = findForceCorePushLv2(p);
                    if (slot != -1) p.getInventory().setItem(slot, null);
                }));

        return java.util.Collections.unmodifiableList(t);
    }

    private static int countMaterial(Player p, Material mat) {
        int count = 0;
        for (ItemStack s : p.getInventory().getContents())
            if (s != null && s.getType() == mat) count += s.getAmount();
        return count;
    }

    private static void removeMaterial(Player p, Material mat, int amount) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != mat) continue;
            if (s.getAmount() <= amount) { amount -= s.getAmount(); p.getInventory().setItem(i, null); }
            else { s.setAmount(s.getAmount() - amount); amount = 0; }
        }
    }

    private static int findForceCorePushLv1(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (isForceCorePush(s) && getForceCorePushLevel(s) == 1) return i;
        }
        return -1;
    }

    private static int findForceCorePushLv2(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (isForceCorePush(s) && getForceCorePushLevel(s) == 2) return i;
        }
        return -1;
    }

    private static final List<LootEntry> LOOT_TABLE = List.of(
        new LootEntry(new ItemStack[]{ vanilla(Material.OAK_LOG, 16, Rarity.COMMON) },                                                          Rarity.COMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.OAK_LOG, 8, Rarity.COMMON), vanilla(Material.COBBLESTONE, 16, Rarity.COMMON) },         Rarity.COMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.COBBLESTONE, 54, Rarity.COMMON) },                                                      Rarity.COMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.IRON_INGOT, 4, Rarity.UNCOMMON) },                                                      Rarity.UNCOMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.IRON_INGOT, 8, Rarity.UNCOMMON) },                                                      Rarity.UNCOMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.GOLDEN_APPLE, 2, Rarity.RARE) },                                                        Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.IRON_SWORD, 1, Rarity.UNCOMMON) },                                                      Rarity.UNCOMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.BOW, 1, Rarity.UNCOMMON), vanilla(Material.ARROW, 16, Rarity.COMMON) },                 Rarity.UNCOMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.ARROW, 16, Rarity.COMMON) },                                                            Rarity.COMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.IRON_CHESTPLATE, 1, Rarity.RARE) },                                                     Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.CRAFTING_TABLE, 1, Rarity.COMMON) },                                                    Rarity.COMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.OBSIDIAN, 4, Rarity.RARE) },                                                            Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.TNT, 1, Rarity.EPIC) },                                                                 Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.DIAMOND, 1, Rarity.RARE) },                                                             Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.DIAMOND, 2, Rarity.EPIC) },                                                        Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.ENDER_PEARL, 1, Rarity.RARE) },                                                         Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.WATER_BUCKET, 1, Rarity.COMMON) },                                                      Rarity.COMMON.weight),
        new LootEntry(new ItemStack[]{ vanilla(Material.ENCHANTING_TABLE, 1, Rarity.EPIC), vanilla(Material.EXPERIENCE_BOTTLE, 5, Rarity.UNCOMMON) }, Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeFireball(1) },                                                                                        Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ makeFireball(3) },                                                                                        Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeTotem() },                                                                                            Rarity.LEGENDARY.weight),
        new LootEntry(new ItemStack[]{ makeBedHealer() },                                                                                        Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ makeForceCorePush() },                                                                                    Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeBedAnchor() },                                                                                        Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeLightningScroll() },                                                                                  Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeNuclearBomb() },                                                                                      Rarity.LEGENDARY.weight),
        new LootEntry(new ItemStack[]{ makeDagger() },                                                                                           Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeQuickCraftingTable() },                                                                               Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ makeTemplate("iron_armor") },                                                                             Rarity.UNCOMMON.weight),
        new LootEntry(new ItemStack[]{ makeTemplate("diamond_armor") },                                                                          Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeTemplate("diamond_tools") },                                                                          Rarity.RARE.weight),
        new LootEntry(new ItemStack[]{ makeTemplate("force_core_push_upgrade") },                                                                Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeTemplate("force_core_push_upgrade_3") },                                                              Rarity.LEGENDARY.weight),
        new LootEntry(new ItemStack[]{ makeBedTrap() },                                                                                          Rarity.UNCOMMON.weight),
        new LootEntry(new ItemStack[]{ makeBlackHole() },                                                                                        Rarity.LEGENDARY.weight),
        new LootEntry(new ItemStack[]{ makePositionSwapper() },                                                                                  Rarity.EPIC.weight),
        new LootEntry(new ItemStack[]{ makeBlockZapper() },                                                                                      Rarity.RARE.weight)
    );

    private static final int LOOT_TOTAL_WEIGHT =
            LOOT_TABLE.stream().mapToInt(LootEntry::weight).sum();

    private static ItemStack[] rollLoot() {
        int roll = RANDOM.nextInt(LOOT_TOTAL_WEIGHT);
        int cumulative = 0;
        for (LootEntry entry : LOOT_TABLE) {
            cumulative += entry.weight();
            if (roll < cumulative) return entry.items();
        }
        return LOOT_TABLE.get(0).items(); // fallback
    }

    /**
     * Returns a stable key→ItemStack map of every distinct item in the loot table.
     * Key is derived from the first item's type + display name (lowercased, spaces→underscores).
     * Used by the admin give command for dynamic tab completion and resolution.
     */
    public static java.util.LinkedHashMap<String, ItemStack> getLootTableItems() {
        java.util.LinkedHashMap<String, ItemStack> map = new java.util.LinkedHashMap<>();
        for (LootEntry entry : LOOT_TABLE) {
            for (ItemStack item : entry.items()) {
                String key = buildItemKey(item);
                map.putIfAbsent(key, item.clone());
            }
        }
        return map;
    }

    private static String buildItemKey(ItemStack item) {
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(meta.displayName());
            // Strip anything that isn't alphanumeric, space, or underscore, then normalize
            return name.toLowerCase().replaceAll("[^a-z0-9 _]", "").trim().replace(' ', '_');
        }
        return item.getType().name().toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Rarity system
    // -------------------------------------------------------------------------

    public enum Rarity {
        COMMON   ("§8COMMON",    net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY,  60),
        UNCOMMON ("§aUNCOMMON",  net.kyori.adventure.text.format.NamedTextColor.GREEN,       30),
        RARE     ("§9RARE",      net.kyori.adventure.text.format.NamedTextColor.BLUE,         15),
        EPIC     ("§5EPIC",      net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE,   5),
        LEGENDARY("§6LEGENDARY", net.kyori.adventure.text.format.NamedTextColor.GOLD,          7);

        public final String label;
        public final net.kyori.adventure.text.format.TextColor color;
        public final int weight;

        Rarity(String label, net.kyori.adventure.text.format.TextColor color, int weight) {
            this.label = label;
            this.color = color;
            this.weight = weight;
        }
    }

    /**
     * Applies a rarity tag to an ItemStack.
     * Adds a blank line then the rarity line at the bottom of the lore.
     * Works on items with or without existing lore.
     */
    public static ItemStack applyRarity(ItemStack item, Rarity rarity) {
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            // Give it a display name matching the material if it has none
            meta = item.getItemMeta();
        }
        // Build display name if not already set
        if (!meta.hasDisplayName()) {
            String name = item.getType().name().toLowerCase().replace('_', ' ');
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            meta.displayName(net.kyori.adventure.text.Component.text(rarity.label.substring(2) + " " + name)
                    .color(rarity.color)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, false));
        }
        // Append rarity lore
        java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>(
                meta.hasLore() ? meta.lore() : java.util.List.of());
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(net.kyori.adventure.text.Component.text(rarity.label)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Convenience: create a vanilla ItemStack with a rarity tag. */
    private static ItemStack vanilla(Material mat, int amount, Rarity rarity) {
        return applyRarity(new ItemStack(mat, amount), rarity);
    }

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

        // Spawn shop NPCs
        for (Location templateLoc : bwMinigame.getShopLocations()) {
            spawnShopVillager(new Location(world,
                    templateLoc.getX(), templateLoc.getY(), templateLoc.getZ()));
        }

        // Mark all configured teams as having a bed
        // Teams with no player assigned get marked as eliminated immediately
        for (GameTeam team : getTeams()) {
            boolean hasBed = bedLocations.containsKey(team.getName().toLowerCase());
            boolean hasPlayer = !team.getMembers().isEmpty();
            bedHealth.put(team.getName().toLowerCase(), (hasBed && hasPlayer) ? BED_MAX_HEALTH : 0);
            if (!hasPlayer || !hasBed) team.setEliminated(true);
        }

        // Spawn health bar armor stands above each bed
        for (Map.Entry<String, Location> entry : bedLocations.entrySet()) {
            spawnBedArmorStand(entry.getKey(), entry.getValue());
        }

        // Teleport each player to their team spawn
        for (GamePlayer gp : getPlayers()) {
            gp.getTeam().ifPresent(team -> {
                teleportToTeamSpawn(gp.getPlayer(), team.getName());
                // Ensure no leftover spectator state from lobby or previous games
                disableInWorldSpectator(gp.getPlayer());
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
        // Remove shop villagers
        for (org.bukkit.entity.Villager v : shopVillagers) {
            if (v != null && !v.isDead()) v.remove();
        }
        shopVillagers.clear();
        // Remove bed armor stands
        for (org.bukkit.entity.ArmorStand stand : bedArmorStands.values()) {
            if (stand != null && !stand.isDead()) stand.remove();
        }
        bedArmorStands.clear();
        // Clean up scoreboard — skip bots (they have no valid connection)
        cc.kokodev.kokopixel.KokoPixel kp = cc.kokodev.kokopixel.KokoPixel.getInstance();
        for (GamePlayer gp : getPlayers()) {
            if (kp.getBotManager().isBot(gp.getUniqueId())) continue;
            gp.getPlayer().setScoreboard(
                    Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    @Override
    protected void onPlayerJoin(GamePlayerImpl player) {
        // Bots earn their own items — don't give them a starter kit
        if (cc.kokodev.kokopixel.KokoPixel.getInstance()
                .getBotManager().isBot(player.getUniqueId())) {
            grantSpawnImmunity(player.getPlayer());
            return;
        }
        // Give starting kit to real players
        player.getPlayer().getInventory().addItem(
                new ItemStack(Material.WOODEN_PICKAXE),
                new ItemStack(Material.WOODEN_SWORD),
                new ItemStack(Material.OAK_LOG, 8),
                new ItemStack(Material.BREAD, 4)
        );
        grantSpawnImmunity(player.getPlayer());
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
                bedHealth.put(teamName, 0);
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
            ItemStack[] loot = rollLoot();
            for (ItemStack item : loot) breaker.getInventory().addItem(item);
            breaker.sendMessage("§a✦ Bed loot: §e" + describeItems(loot));
            breaker.playSound(breaker.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return true; // cancel the break — bed stays
        } else {
            // Enemy hit — check for Bed Trap first
            UUID trapOwner = bedTraps.get(bedTeam.toLowerCase());
            if (trapOwner != null && !trapOwner.equals(breaker.getUniqueId())) {
                // Trap fires — consume it, launch the breaker
                bedTraps.remove(bedTeam.toLowerCase());
                // Direction breaker is facing — launch backwards and up
                org.bukkit.util.Vector back = breaker.getLocation().getDirection()
                        .multiply(-1).setY(0).normalize().multiply(1.8);
                back.setY(0.9); // ~10 blocks up, 6 back
                breaker.setVelocity(back);
                breaker.sendMessage("§c§lBED TRAP! §7You triggered a trap on §e" + bedTeam + "§7's bed!");
                breaker.playSound(breaker.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 0.8f);
                breaker.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, bedBlock.getLocation().add(0.5, 0.5, 0.5), 1);
                // Notify trap owner
                Player owner = Bukkit.getPlayer(trapOwner);
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage("§a§lBed Trap §7triggered on §e" + bedTeam + "§7's bed — §c" + breaker.getName() + " §7was launched!");
                    owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                }
                return true; // cancel the break
            }

            // Enemy hit — check for Bed Anchor first
            int health = bedHealth.getOrDefault(bedTeam.toLowerCase(), 0);
            if (health <= 0) return false; // already dead, let vanilla handle

            if (bedAnchors.getOrDefault(bedTeam.toLowerCase(), 0) > 0) {
                // Consume one anchor
                int remaining = bedAnchors.merge(bedTeam.toLowerCase(), -1, Integer::sum);
                if (remaining <= 0) bedAnchors.remove(bedTeam.toLowerCase());
                final int anchorsLeft = Math.max(0, remaining);
                getTeam(bedTeam).ifPresent(team -> {
                    for (GamePlayer gp : team.getMembers()) {
                        gp.getPlayer().sendMessage("§6§lBed Anchor §7absorbed a hit from §e" + breaker.getName()
                                + "§7! §6" + anchorsLeft + " anchor(s) remaining.");
                        gp.getPlayer().playSound(gp.getPlayer().getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 1.5f);
                    }
                });
                breaker.sendMessage("§c✦ Your hit on §e" + bedTeam + "§c's bed was blocked by a §6§lBed Anchor§c!");
                breaker.playSound(breaker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 1.5f);
                updateScoreboard();
                return true; // cancel the break — anchor consumed, bed untouched
            }

            health--;
            bedHealth.put(bedTeam.toLowerCase(), health);

            if (health <= 0) {
                // Fully destroyed
                destroyBed(bedTeam, breaker);
                return false; // let the block break normally
            } else {
                // Damaged but not destroyed — cancel the break, show damage feedback
                final int finalHealth = health;
                getTeam(bedTeam).ifPresent(team -> {
                    for (GamePlayer gp : team.getMembers()) {
                        gp.getPlayer().sendMessage("§c§lYour bed was hit by §e" + breaker.getName()
                                + "§c! Health: " + buildHealthBar(finalHealth));
                        gp.getPlayer().playSound(gp.getPlayer().getLocation(),
                                Sound.ENTITY_IRON_GOLEM_HURT, 1f, 1.2f);
                    }
                });
                breaker.sendMessage("§e✦ Hit §c" + bedTeam + "§e's bed! " + buildHealthBar(finalHealth));
                breaker.playSound(breaker.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 1f, 1.2f);
                updateScoreboard();
                updateBedArmorStand(bedTeam);
                return true; // cancel — bed stays until health reaches 0
            }
        }
    }

    private void destroyBed(String teamName, Player destroyer) {
        bedHealth.put(teamName.toLowerCase(), 0);

        // Remove the armor stand
        org.bukkit.entity.ArmorStand stand = bedArmorStands.remove(teamName.toLowerCase());
        if (stand != null && !stand.isDead()) stand.remove();

        // Find the team's player and notify them
        getTeam(teamName).ifPresent(team -> {
            String destroyerName = destroyer != null ? destroyer.getName() : "unknown";
            cc.kokodev.kokopixel.KokoPixel kp = cc.kokodev.kokopixel.KokoPixel.getInstance();
            for (GamePlayer gp : team.getMembers()) {
                if (kp.getBotManager().isBot(gp.getUniqueId())) continue;
                Player member = gp.getPlayer();
                // If the player is already dead (on death screen / respawn countdown), they died
                // BEFORE the bed was broken — don't show "You will not respawn", they already died
                // with the bed intact so their current respawn countdown should continue.
                if (spectatorPlayers.contains(member.getUniqueId())) {
                    member.sendMessage("§c§lYour bed was destroyed by §e" + destroyerName
                            + "§c §7while you were dead. You may still respawn this time, but not again!");
                    continue;
                }
                member.sendMessage("§c§lYour bed was destroyed by §e" + destroyerName + "§c!");
                member.showTitle(
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
                + " bedHealth=" + bedHealth);

        if (teamName == null) {
            plugin.getLogger().warning("[BedWars] No team found for " + player.getName());
            return;
        }

        boolean hasBed = bedHealth.getOrDefault(teamName.toLowerCase(), 0) > 0;
        boolean totemOverride = totemRespawnOverride.remove(player.getUniqueId());

        if (hasBed || totemOverride) {
            if (totemOverride && !hasBed) {
                player.sendMessage("§6§lTotem of Keep Inventory §7saved you — respawning without a bed!");
            }
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
            grantSpawnImmunity(player);
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
        spectatorPlayers.add(player.getUniqueId());
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        // Keep collidable so living players can push through the spectator
        player.setCanPickupItems(false);
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE, 0, false, false, false));
    }

    /** Reverses enableInWorldSpectator — restores normal survival state. */
    private void disableInWorldSpectator(Player player) {
        spectatorPlayers.remove(player.getUniqueId());
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.setCanPickupItems(true);
        player.setCollidable(true);
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
    // Shop NPC
    // -------------------------------------------------------------------------

    /** PDC key used to mark shop villager entities so we can identify them on interact. */
    private static final org.bukkit.NamespacedKey SHOP_KEY =
            new org.bukkit.NamespacedKey("obbedwars", "shop_villager");

    /** Shop GUI title — used to identify the inventory in click events. */
    public static final String SHOP_GUI_TITLE = "Item Shop";

    /** Shop offers: each entry is { cost in diamonds, rarity/item label, ItemStack to give } */
    public record ShopOffer(int cost, String label, ItemStack item) {}

    private static final List<ShopOffer> SHOP_OFFERS = buildShopOffers();

    private static List<ShopOffer> buildShopOffers() {
        List<ShopOffer> offers = new ArrayList<>();
        // Blocks
        offers.add(new ShopOffer(1,  "§864x Cobblestone",  vanilla(Material.COBBLESTONE, 64, Rarity.COMMON)));
        offers.add(new ShopOffer(1,  "§832x Oak Log",       vanilla(Material.OAK_LOG, 32, Rarity.COMMON)));
        offers.add(new ShopOffer(8,  "§816x Obsidian",      vanilla(Material.OBSIDIAN, 16, Rarity.RARE)));
        // Chests — roll a random item of the given rarity from the loot table
        offers.add(new ShopOffer(4,  "§aUncommon Chest",   makeRarityChest(Rarity.UNCOMMON)));
        offers.add(new ShopOffer(8,  "§9Rare Chest",        makeRarityChest(Rarity.RARE)));
        offers.add(new ShopOffer(16,  "§5Epic Chest",        makeRarityChest(Rarity.EPIC)));
        offers.add(new ShopOffer(32, "§6Legendary Chest",   makeRarityChest(Rarity.LEGENDARY)));
        return offers;
    }

    private void spawnShopVillager(Location loc) {
        org.bukkit.entity.Villager v = world.spawn(loc, org.bukkit.entity.Villager.class, villager -> {
            villager.setAI(false);
            villager.setInvulnerable(true);
            villager.setCollidable(false);
            villager.setSilent(false);
            villager.setVillagerType(org.bukkit.entity.Villager.Type.PLAINS);
            villager.setProfession(org.bukkit.entity.Villager.Profession.CARTOGRAPHER);
            villager.customName(net.kyori.adventure.text.Component.text("§6§lItem Shop")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            villager.setCustomNameVisible(true);
            villager.getPersistentDataContainer().set(SHOP_KEY,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        shopVillagers.add(v);
    }

    public boolean isShopVillager(org.bukkit.entity.Entity entity) {
        if (!(entity instanceof org.bukkit.entity.Villager v)) return false;
        return v.getPersistentDataContainer().has(SHOP_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public void openShopGui(Player player) {
        int rows = (int) Math.ceil(SHOP_OFFERS.size() / 9.0);
        rows = Math.max(1, Math.min(rows, 6));
        org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, rows * 9,
                net.kyori.adventure.text.Component.text("Item Shop")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));

        for (int i = 0; i < SHOP_OFFERS.size(); i++) {
            ShopOffer offer = SHOP_OFFERS.get(i);
            ItemStack display = offer.item().clone();
            org.bukkit.inventory.meta.ItemMeta meta = display.getItemMeta();
            // Append cost hint to lore
            java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>(
                    meta.hasLore() ? meta.lore() : java.util.List.of());
            lore.add(net.kyori.adventure.text.Component.empty());
            lore.add(net.kyori.adventure.text.Component.text("§7Cost: §b" + offer.cost() + " Diamond(s)")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lore.add(net.kyori.adventure.text.Component.text("§eClick to purchase")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            // Store slot index in PDC so click handler knows which offer was clicked
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "shop_slot"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, i);
            meta.lore(lore);
            display.setItemMeta(meta);
            gui.setItem(i, display);
        }
        player.openInventory(gui);
    }

    /**
     * Handles a click inside the shop GUI.
     * @return true if the purchase succeeded, false if not enough diamonds
     */
    public boolean handleShopClick(Player player, int slot) {
        if (slot < 0 || slot >= SHOP_OFFERS.size()) return false;
        ShopOffer offer = SHOP_OFFERS.get(slot);

        // Count diamonds in inventory
        int diamonds = 0;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && s.getType() == Material.DIAMOND) diamonds += s.getAmount();
        }
        if (diamonds < offer.cost()) {
            player.sendMessage("§cNot enough diamonds! Need §b" + offer.cost() + "§c, have §b" + diamonds + "§c.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }

        // Deduct diamonds
        int toRemove = offer.cost();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != Material.DIAMOND) continue;
            if (s.getAmount() <= toRemove) {
                toRemove -= s.getAmount();
                player.getInventory().setItem(i, null);
            } else {
                s.setAmount(s.getAmount() - toRemove);
                toRemove = 0;
            }
        }

        // Give item — for rarity chests, give the chest itself to open later
        ItemStack reward = offer.item().clone();
        // Strip the shop_slot PDC from the given item so it's a clean chest
        org.bukkit.inventory.meta.ItemMeta rewardMeta = reward.getItemMeta();
        if (rewardMeta != null) {
            rewardMeta.getPersistentDataContainer().remove(
                    new org.bukkit.NamespacedKey("obbedwars", "shop_slot"));
            reward.setItemMeta(rewardMeta);
        }
        player.getInventory().addItem(reward);
        player.sendMessage("§aPurchased §e" + offer.label() + "§a!");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        return true;
    }

    /** Resolves the actual reward — rarity chests roll a fresh item on purchase. */
    private ItemStack resolveReward(ShopOffer offer) {
        org.bukkit.inventory.meta.ItemMeta meta = offer.item().getItemMeta();
        if (meta == null) return offer.item().clone();
        String rarityTag = meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rarity"),
                org.bukkit.persistence.PersistentDataType.STRING);
        if (rarityTag == null) return offer.item().clone();
        Rarity rarity = Rarity.valueOf(rarityTag);
        return rollLootByRarity(rarity);
    }

    /** Rolls a random item from the loot table matching the given rarity. */
    private static ItemStack rollLootByRarity(Rarity rarity) {
        List<LootEntry> pool = LOOT_TABLE.stream()
                .filter(e -> getRarityOf(e.items()[0]) == rarity)
                .toList();
        if (pool.isEmpty()) return new ItemStack(Material.DIAMOND); // fallback
        LootEntry entry = pool.get(RANDOM.nextInt(pool.size()));
        return entry.items()[0].clone();
    }

    /** Reads the rarity of an item from its lore PDC (set by applyRarity). */
    private static Rarity getRarityOf(ItemStack item) {
        if (item == null) return Rarity.COMMON;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return Rarity.COMMON;
        // Match lore text against rarity labels
        if (meta.hasLore()) {
            for (net.kyori.adventure.text.Component line : meta.lore()) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(line).toUpperCase();
                for (Rarity r : Rarity.values()) {
                    if (plain.equals(r.name())) return r;
                }
            }
        }
        return Rarity.COMMON;
    }

    /** Creates a chest item tagged with a rarity — opens to a random item of that rarity on purchase. */
    private static ItemStack makeRarityChest(Rarity rarity) {
        ItemStack item = new ItemStack(Material.CHEST, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(rarity.label + " Chest")
                .color(rarity.color)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Contains a random §r" + rarity.label + " §7item.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rarity"),
                org.bukkit.persistence.PersistentDataType.STRING, rarity.name());
        item.setItemMeta(meta);
        return item;
    }

    public static List<ShopOffer> getShopOffers() { return SHOP_OFFERS; }

    // -------------------------------------------------------------------------
    // Rarity Chest — slot machine GUI
    // -------------------------------------------------------------------------

    public static final String CHEST_GUI_TITLE_PREFIX = "Opening: ";
    // Slot in the 3-row GUI where the result is shown (center)
    private static final int CHEST_RESULT_SLOT = 13;
    // Reroll button slots
    private static final int[] CHEST_REROLL_SLOTS = {20, 22, 24};

    public static boolean isRarityChest(ItemStack item) {
        if (item == null || item.getType() != Material.CHEST) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rarity"),
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    public static String getChestRarity(ItemStack item) {
        if (item == null) return null;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rarity"),
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    /**
     * Opens the slot-machine chest GUI for a player.
     * Consumes the chest item from their hand.
     */
    public void openChestGui(Player player, ItemStack chestItem) {
        String rarityName = getChestRarity(chestItem);
        if (rarityName == null) return;
        Rarity rarity = Rarity.valueOf(rarityName);

        // Consume the chest
        chestItem.setAmount(chestItem.getAmount() - 1);

        List<ItemStack> pool = LOOT_TABLE.stream()
                .filter(e -> getRarityOf(e.items()[0]) == rarity)
                .map(e -> e.items()[0].clone())
                .toList();
        if (pool.isEmpty()) { player.sendMessage("§cNo items available for this rarity!"); return; }

        String guiTitle = CHEST_GUI_TITLE_PREFIX + rarity.label;
        org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, 27,
                net.kyori.adventure.text.Component.text(guiTitle)
                        .color(rarity.color)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));

        // Fill background
        ItemStack filler = fillerPane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) gui.setItem(i, filler);

        // Initial random result
        ItemStack result = pool.get(RANDOM.nextInt(pool.size()));
        gui.setItem(CHEST_RESULT_SLOT, result);

        // Reroll buttons (3 uses)
        updateRerollButtons(gui, 3);

        // Store rerolls + rarity in the GUI via a hidden item in slot 0
        ItemStack state = new ItemStack(Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta sm = state.getItemMeta();
        sm.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rerolls"),
                org.bukkit.persistence.PersistentDataType.INTEGER, 3);
        sm.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rarity"),
                org.bukkit.persistence.PersistentDataType.STRING, rarityName);
        sm.displayName(net.kyori.adventure.text.Component.text(" "));
        state.setItemMeta(sm);
        gui.setItem(0, state);

        // Claim button
        gui.setItem(26, buildClaimButton(result));

        player.openInventory(gui);
        startSpinAnimation(player, gui, pool, rarity, 0);
    }

    private void startSpinAnimation(Player player, org.bukkit.inventory.Inventory gui,
                                    List<ItemStack> pool, Rarity rarity, int tick) {
        if (tick >= 16) return; // 16 ticks of spinning (~0.8s)
        runTaskLater(() -> {
            if (player.getOpenInventory().getTopInventory() != gui) return;
            ItemStack spin = pool.get(RANDOM.nextInt(pool.size()));
            gui.setItem(CHEST_RESULT_SLOT, spin);
            gui.setItem(26, buildClaimButton(spin));
            float pitch = 0.5f + (tick / 16f) * 1.5f; // pitch rises as it slows
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, pitch);
            startSpinAnimation(player, gui, pool, rarity, tick + 1);
        }, tick < 8 ? 2L : tick < 12 ? 3L : 4L); // slow down toward end
    }

    private static void updateRerollButtons(org.bukkit.inventory.Inventory gui, int rerolls) {
        for (int i = 0; i < CHEST_REROLL_SLOTS.length; i++) {
            ItemStack btn;
            if (i < rerolls) {
                btn = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                org.bukkit.inventory.meta.ItemMeta m = btn.getItemMeta();
                m.displayName(net.kyori.adventure.text.Component.text("§aReroll (" + rerolls + " left)")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                btn.setItemMeta(m);
            } else {
                btn = fillerPane(Material.RED_STAINED_GLASS_PANE);
            }
            gui.setItem(CHEST_REROLL_SLOTS[i], btn);
        }
    }

    private static ItemStack buildClaimButton(ItemStack result) {
        ItemStack btn = new ItemStack(Material.EMERALD_BLOCK);
        org.bukkit.inventory.meta.ItemMeta m = btn.getItemMeta();
        m.displayName(net.kyori.adventure.text.Component.text("§a§lCLAIM")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        String itemName = result.getItemMeta() != null && result.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(result.getItemMeta().displayName())
                : result.getType().name().toLowerCase().replace('_', ' ');
        m.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Click to claim: §f" + itemName)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        btn.setItemMeta(m);
        return btn;
    }

    private static ItemStack fillerPane(Material mat) {
        ItemStack p = new ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta m = p.getItemMeta();
        m.displayName(net.kyori.adventure.text.Component.text(" "));
        p.setItemMeta(m);
        return p;
    }

    /**
     * Handles a click inside the chest slot-machine GUI.
     * Called from the listener.
     */
    public void handleChestGuiClick(Player player, org.bukkit.inventory.Inventory gui,
                                    int rawSlot, String rarityName) {
        // Read state from slot 0
        ItemStack state = gui.getItem(0);
        if (state == null) return;
        org.bukkit.inventory.meta.ItemMeta sm = state.getItemMeta();
        if (sm == null) return;
        int rerolls = sm.getPersistentDataContainer().getOrDefault(
                new org.bukkit.NamespacedKey("obbedwars", "chest_rerolls"),
                org.bukkit.persistence.PersistentDataType.INTEGER, 0);

        Rarity rarity = Rarity.valueOf(rarityName);
        List<ItemStack> pool = LOOT_TABLE.stream()
                .filter(e -> getRarityOf(e.items()[0]) == rarity)
                .map(e -> e.items()[0].clone())
                .toList();

        // Reroll button clicked
        boolean isReroll = false;
        for (int rs : CHEST_REROLL_SLOTS) { if (rs == rawSlot) { isReroll = true; break; } }

        if (isReroll) {
            if (rerolls <= 0) { player.sendMessage("§cNo rerolls left!"); return; }
            rerolls--;
            sm.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "chest_rerolls"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, rerolls);
            state.setItemMeta(sm);
            gui.setItem(0, state);
            updateRerollButtons(gui, rerolls);
            startSpinAnimation(player, gui, pool, rarity, 0);
            return;
        }

        // Claim button clicked
        if (rawSlot == 26) {
            ItemStack result = gui.getItem(CHEST_RESULT_SLOT);
            if (result == null || result.getType() == Material.AIR) return;
            player.closeInventory();
            player.getInventory().addItem(result.clone());
            player.sendMessage("§a§lChest opened! §7You received: §f"
                    + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(result.getItemMeta() != null && result.getItemMeta().hasDisplayName()
                                    ? result.getItemMeta().displayName()
                                    : net.kyori.adventure.text.Component.text(result.getType().name())));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        }
    }

    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = scoreboard.registerNewObjective("bedwars", Criteria.DUMMY,
                net.kyori.adventure.text.Component.text("§6§lBEDWARS"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // No-collision team — all players are added here so they never push each other
        Team noCollision = scoreboard.registerNewTeam("no_collision");
        noCollision.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        for (GamePlayer gp : getPlayers()) noCollision.addPlayer(gp.getPlayer());

        updateScoreboard();
        cc.kokodev.kokopixel.KokoPixel kpSb = cc.kokodev.kokopixel.KokoPixel.getInstance();
        for (GamePlayer gp : getPlayers()) {
            if (kpSb.getBotManager().isBot(gp.getUniqueId())) continue;
            gp.getPlayer().setScoreboard(scoreboard);
        }
    }

    private void updateScoreboard() {
        if (objective == null) return;
        // Reset all scores
        for (String entry : new HashSet<>(scoreboard.getEntries())) scoreboard.resetScores(entry);

        List<GameTeam> allTeams = getTeams();
        int line = allTeams.size() * 3; // 3 lines per team: name + health bar + anchors

        for (GameTeam team : allTeams) {
            ChatColor color = team.getColor();
            String teamKey = team.getName().toLowerCase();
            int health = bedHealth.getOrDefault(teamKey, 0);
            int anchors = bedAnchors.getOrDefault(teamKey, 0);

            String playerName = team.getMembers().isEmpty() ? "" :
                    team.getMembers().get(0).getPlayer().getName();
            // Max scoreboard entry is 40 chars; colour codes don't count toward display width
            // but do count toward the string length Bukkit stores. Budget conservatively.
            String teamLabel = color + "§l" + team.getName().toUpperCase();
            if (!playerName.isEmpty()) {
                String suffix = " §r§7" + playerName;
                // Truncate player name if the combined entry would exceed 40 chars
                int budget = 40 - teamLabel.length() - " §r§7".length();
                if (budget <= 0) {
                    suffix = ""; // no room at all
                } else if (playerName.length() > budget) {
                    suffix = " §r§7" + playerName.substring(0, Math.max(1, budget - 2)) + "..";
                }
                teamLabel += suffix;
            }
            String nameEntry = teamLabel;
            while (scoreboard.getEntries().contains(nameEntry)) nameEntry += " ";
            objective.getScore(nameEntry).setScore(line--);

            String barEntry;
            if (team.isEliminated() || team.getMembers().isEmpty()) {
                barEntry = "§c§lELIMINATED";
            } else if (health <= 0) {
                barEntry = "§c§lBED GONE";
            } else {
                barEntry = buildHealthBar(health);
            }
            while (scoreboard.getEntries().contains(barEntry)) barEntry += " ";
            objective.getScore(barEntry).setScore(line--);

            String anchorEntry = anchors > 0 ? "§6⚓ " + anchors + " Anchor" + (anchors > 1 ? "s" : "") : "§8No Anchors";
            while (scoreboard.getEntries().contains(anchorEntry)) anchorEntry += " ";
            objective.getScore(anchorEntry).setScore(line--);
        }
    }

    // -------------------------------------------------------------------------
    // Bed health bar & armor stand
    // -------------------------------------------------------------------------

    /** Builds a colored progress bar string. Green = remaining, red = lost. */
    private String buildHealthBar(int health) {
        int green = (int) Math.round((double) health / BED_MAX_HEALTH * HEALTH_BAR_SEGMENTS);
        int red = HEALTH_BAR_SEGMENTS - green;
        return "§a" + "|".repeat(green) + "§c" + "|".repeat(red);
    }

    private void spawnBedArmorStand(String teamName, Location bedLoc) {
        // Place the stand 1.5 blocks above the bed foot block
        Location standLoc = bedLoc.clone().add(0.5, 1.5, 0.5);
        org.bukkit.entity.ArmorStand stand = world.spawn(standLoc, org.bukkit.entity.ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSmall(true);
            s.setMarker(true);
            s.setCustomNameVisible(true);
            s.customName(buildHealthBarComponent(BED_MAX_HEALTH));
        });
        bedArmorStands.put(teamName.toLowerCase(), stand);
    }

    private void updateBedArmorStand(String teamName) {
        org.bukkit.entity.ArmorStand stand = bedArmorStands.get(teamName.toLowerCase());
        if (stand == null || stand.isDead()) return;
        int health = bedHealth.getOrDefault(teamName.toLowerCase(), 0);
        stand.customName(buildHealthBarComponent(health));
    }

    private net.kyori.adventure.text.Component buildHealthBarComponent(int health) {
        int green = (int) Math.round((double) health / BED_MAX_HEALTH * HEALTH_BAR_SEGMENTS);
        int red = HEALTH_BAR_SEGMENTS - green;
        return net.kyori.adventure.text.Component.empty()
                .append(net.kyori.adventure.text.Component.text("|".repeat(green))
                        .color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                .append(net.kyori.adventure.text.Component.text("|".repeat(red))
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED));
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
        return applyRarity(item, amount >= 3 ? Rarity.EPIC : Rarity.RARE);
    }

    public static boolean isFireball(ItemStack item) {
        if (item == null || item.getType() != Material.FIRE_CHARGE) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "fireball"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Creates a Totem of Keep Inventory item. */
    public static ItemStack makeTotem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§6§lTotem of Keep Inventory")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Keep your items on death.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8Consumed on use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "keep_inv_totem"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.LEGENDARY);
    }

    public static boolean isTotem(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "keep_inv_totem"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Creates a Bed Healer item — right-click to restore one bed health step. */
    public static ItemStack makeBedHealer() {
        ItemStack item = new ItemStack(Material.RECOVERY_COMPASS, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§b§lBed Healer")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to restore your bed by 1 health.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "bed_healer"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.RARE);
    }

    public static boolean isBedHealer(ItemStack item) {
        if (item == null || item.getType() != Material.RECOVERY_COMPASS) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "bed_healer"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Creates a Jedi Force Core - Push item at the given level (1 or 2). */
    public static ItemStack makeForceCorePush() { return makeForceCorePush(1); }
    public static ItemStack makeForceCorePush(int level) {
        String title = switch (level) {
            case 2  -> "§5§lJedi Force Core - Push §dII";
            case 3  -> "§6§lJedi Force Core - Push §eIII";
            default -> "§d§lJedi Force Core - Push";
        };
        double range  = switch (level) { case 2 -> 6; case 3 -> 10; default -> 3; };
        double force  = switch (level) { case 2 -> 5; case 3 -> 8;  default -> 3; };
        int    cd     = switch (level) { case 2 -> 7; case 3 -> 5;  default -> 10; };
        Rarity rarity = switch (level) { case 2 -> Rarity.LEGENDARY; case 3 -> Rarity.LEGENDARY; default -> Rarity.EPIC; };

        ItemStack item = new ItemStack(Material.PAPER, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(title)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Range: §f" + (int) range + " blocks")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Push force: §f" + (int) force + " blocks")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8Cooldown: " + cd + "s")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "force_core_push"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "force_core_level"),
                org.bukkit.persistence.PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
        return applyRarity(item, rarity);
    }

    public static boolean isForceCorePush(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "force_core_push"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public static int getForceCorePushLevel(ItemStack item) {
        if (item == null) return 1;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return 1;
        Integer lvl = meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey("obbedwars", "force_core_level"),
                org.bukkit.persistence.PersistentDataType.INTEGER);
        return lvl != null ? lvl : 1;
    }

    /** Creates a Bed Anchor item — right-click to protect your bed from the next hit. */
    public static ItemStack makeBedAnchor() {
        ItemStack item = new ItemStack(Material.ANVIL, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§6§lBed Anchor")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to anchor your bed.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7The next enemy hit is completely absorbed.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "bed_anchor"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.EPIC);
    }

    public static boolean isBedAnchor(ItemStack item) {
        if (item == null || item.getType() != Material.ANVIL) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "bed_anchor"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Creates a Lightning Scroll item. */
    public static ItemStack makeLightningScroll() {
        ItemStack item = new ItemStack(Material.WIND_CHARGE, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§e§lLightning Scroll")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to choose a target.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "lightning_scroll"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.EPIC);
    }

    public static boolean isLightningScroll(ItemStack item) {
        if (item == null || item.getType() != Material.WIND_CHARGE) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "lightning_scroll"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Creates a Nuclear Bomb item. */
    public static ItemStack makeNuclearBomb() {
        ItemStack item = new ItemStack(Material.TNT, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§4§lNuclear Bomb")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to choose a target base.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Rains TNT and lightning on their bed.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "nuclear_bomb"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.LEGENDARY);
    }

    public static boolean isNuclearBomb(ItemStack item) {
        if (item == null || item.getType() != Material.TNT) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "nuclear_bomb"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // -------------------------------------------------------------------------
    // Black Hole
    // -------------------------------------------------------------------------

    private static final double BH_PULL_RADIUS  = 10.0; // blocks — start pulling
    private static final double BH_CORE_RADIUS  = 2.0;  // blocks — damage + trap zone
    private static final int    BH_DURATION_TICKS = 600; // 30 seconds
    private static final int    BH_TICK_INTERVAL  = 4;   // run every 4 ticks (~5x/sec)

    public static ItemStack makeBlackHole() {
        ItemStack item = new ItemStack(Material.ENDER_EYE, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§8§lBlack Hole")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to deploy.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Pulls everyone within 10 blocks toward it.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Players inside take damage and can't escape.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8Lasts 30 seconds. One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "black_hole"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.LEGENDARY);
    }

    public static boolean isBlackHole(ItemStack item) {
        if (item == null || item.getType() != Material.ENDER_EYE) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "black_hole"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // -------------------------------------------------------------------------
    // Position Swapper
    // -------------------------------------------------------------------------

    public static final String SWAPPER_GUI_TITLE = "§5§lPosition Swapper — Choose Target";

    public static ItemStack makePositionSwapper() {
        ItemStack item = new ItemStack(Material.COMPASS, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§5§lPosition Swapper")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to choose a target.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Swaps your position with theirs.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Grants 3s of spawn immunity after swap.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "position_swapper"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.EPIC);
    }

    public static boolean isPositionSwapper(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "position_swapper"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // -------------------------------------------------------------------------
    // Block Zapper
    // -------------------------------------------------------------------------

    public static ItemStack makeBlockZapper() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§e§lBlock Zapper")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Break any block instantly.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "block_zapper"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.RARE);
    }

    public static boolean isBlockZapper(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "block_zapper"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public void openSwapperGui(Player user) {
        String userTeam = getPlayerTeamName(user);
        java.util.List<Player> targets = new java.util.ArrayList<>();
        for (cc.kokodev.kokopixel.api.game.GamePlayer gp : getPlayers()) {
            Player p = gp.getPlayer();
            if (p.equals(user)) continue;
            if (isSpectator(p.getUniqueId())) continue;
            String t = getPlayerTeamName(p);
            if (userTeam != null && userTeam.equals(t)) continue;
            targets.add(p);
        }

        if (targets.isEmpty()) { user.sendMessage("§cNo valid targets!"); return; }

        int size = Math.max(9, (int) Math.ceil(targets.size() / 9.0) * 9);
        org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, size,
                net.kyori.adventure.text.Component.text(SWAPPER_GUI_TITLE));

        for (Player target : targets) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta sm = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(target);
            sm.displayName(net.kyori.adventure.text.Component.text("§c" + target.getName())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            sm.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text("§7Click to swap positions!")
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            sm.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "swap_target"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    target.getUniqueId().toString());
            head.setItemMeta(sm);
            gui.addItem(head);
        }
        user.openInventory(gui);
    }

    /**
     * Deploys a black hole at the given location for 30 seconds.
     * Pulls all nearby players toward the center; traps and damages those inside the core.
     */
    public void activateBlackHole(Location center, Player owner) {
        String ownerTeam = getPlayerTeamName(owner);
        broadcast("§8§l☯ BLACK HOLE §7deployed by §c" + owner.getName() + "§7!");

        // Spawn a visual marker — a small invisible armor stand at the center
        org.bukkit.entity.ArmorStand marker = world.spawn(center, org.bukkit.entity.ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setMarker(true);
            s.setSmall(true);
            s.setCustomNameVisible(true);
            s.customName(net.kyori.adventure.text.Component.text("§8§l☯ BLACK HOLE")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        });

        final int[] ticksLeft = {BH_DURATION_TICKS};

        runTaskTimer(() -> {
            ticksLeft[0] -= BH_TICK_INTERVAL;

            if (ticksLeft[0] <= 0 || getState() != GameState.ACTIVE) {
                marker.remove();
                // Release all trapped players
                for (Player p : world.getPlayers()) {
                    if (isSpectator(p.getUniqueId())) continue;
                    p.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
                }
                if (ticksLeft[0] <= 0)
                    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
                return;
            }

            // Particle ring to visualise the black hole
            world.spawnParticle(Particle.PORTAL, center, 30, 1.5, 1.5, 1.5, 0.3);
            world.spawnParticle(Particle.SMOKE, center, 10, 0.3, 0.3, 0.3, 0.05);
            // Play ambient sound only every ~2 seconds (40 ticks) to avoid spam
            if (ticksLeft[0] % 40 == 0) {
                world.playSound(center, Sound.BLOCK_PORTAL_AMBIENT, 0.4f, 0.5f);
            }

            for (Player p : world.getPlayers()) {
                if (isSpectator(p.getUniqueId())) continue;
                // Don't affect the owner's teammates
                String pTeam = getPlayerTeamName(p);
                if (ownerTeam != null && ownerTeam.equals(pTeam)) continue;

                double dist = p.getLocation().distance(center);

                if (dist <= BH_CORE_RADIUS) {
                    // Inside core — damage, blindness, pull back to center
                    p.damage(1.5); // 0.75 hearts per tick interval (~3.75 hearts/sec)
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.BLINDNESS,
                            BH_TICK_INTERVAL + 2, 0, false, false, false));
                    // Continuously drag them back to center so they can't walk out
                    org.bukkit.util.Vector toCenter = center.toVector()
                            .subtract(p.getLocation().toVector()).normalize().multiply(0.5);
                    toCenter.setY(Math.max(toCenter.getY(), 0.1));
                    p.setVelocity(toCenter);

                } else if (dist <= BH_PULL_RADIUS) {
                    // Outer ring — pull toward center, strength increases with proximity
                    double strength = 0.08 + 0.12 * (1.0 - dist / BH_PULL_RADIUS);
                    org.bukkit.util.Vector pull = center.toVector()
                            .subtract(p.getLocation().toVector()).normalize().multiply(strength);
                    pull.setY(pull.getY() * 0.3); // dampen vertical pull so they don't fly up
                    p.setVelocity(p.getVelocity().add(pull));
                }
            }
        }, 0L, BH_TICK_INTERVAL);
    }

    /** Maps a ChatColor to the closest wool Material. */
    public static Material chatColorToWool(ChatColor color) {
        return switch (color) {
            case RED           -> Material.RED_WOOL;
            case BLUE          -> Material.BLUE_WOOL;
            case GREEN         -> Material.GREEN_WOOL;
            case YELLOW        -> Material.YELLOW_WOOL;
            case AQUA          -> Material.CYAN_WOOL;
            case LIGHT_PURPLE  -> Material.MAGENTA_WOOL;
            case GOLD          -> Material.ORANGE_WOOL;
            case WHITE         -> Material.WHITE_WOOL;
            case GRAY          -> Material.GRAY_WOOL;
            case DARK_GREEN    -> Material.GREEN_WOOL;
            case DARK_RED      -> Material.RED_WOOL;
            case DARK_BLUE     -> Material.BLUE_WOOL;
            case DARK_AQUA     -> Material.CYAN_WOOL;
            case DARK_PURPLE   -> Material.PURPLE_WOOL;
            case DARK_GRAY     -> Material.GRAY_WOOL;
            default            -> Material.WHITE_WOOL;
        };
    }

    public static final String NUCLEAR_GUI_TITLE = "§4§lNuclear Bomb — Choose Target";

    /** Opens the Nuclear Bomb target selector GUI. */
    public void openNuclearBombGui(Player user) {
        String userTeam = getPlayerTeamName(user);
        java.util.List<GameTeam> targets = getTeams().stream()
                .filter(t -> !t.isEliminated() && !t.getMembers().isEmpty())
                .filter(t -> !t.getName().equalsIgnoreCase(userTeam))
                .toList();

        if (targets.isEmpty()) { user.sendMessage("§cNo valid targets!"); return; }

        int size = Math.max(9, (int) Math.ceil(targets.size() / 9.0) * 9);
        org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, size,
                net.kyori.adventure.text.Component.text(NUCLEAR_GUI_TITLE));

        for (GameTeam team : targets) {
            ItemStack wool = new ItemStack(chatColorToWool(team.getColor()));
            org.bukkit.inventory.meta.ItemMeta m = wool.getItemMeta();
            m.displayName(net.kyori.adventure.text.Component.text(
                    team.getColor() + "§l" + team.getName().toUpperCase() + " BASE")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            m.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text("§7Click to nuke this base!")
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            m.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "nuke_target"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    team.getName().toLowerCase());
            wool.setItemMeta(m);
            gui.addItem(wool);
        }
        user.openInventory(gui);
    }

    /** Strikes a base with TNT and lightning. Called from the listener after GUI click. */
    public void strikeNuclearBomb(String teamName, Player user) {
        Location bedLoc = bedLocations.get(teamName.toLowerCase());
        if (bedLoc == null) { user.sendMessage("§cCould not find that base!"); return; }

        Location center = bedLoc.clone().add(0.5, 3, 0.5);

        // 8 TNT spread around the bed + 4 lightning strikes — enough impact, not laggy
        int[] offsets = {-2, 0, 2};
        for (int dx : offsets) {
            for (int dz : offsets) {
                if (dx == 0 && dz == 0) continue; // skip exact center for variety
                Location tntLoc = center.clone().add(dx, 0, dz);
                world.spawn(tntLoc, org.bukkit.entity.TNTPrimed.class, tnt -> {
                    tnt.setFuseTicks(10 + RANDOM.nextInt(20)); // stagger detonations
                    tnt.setSource(user);
                });
            }
        }
        // One central TNT
        world.spawn(center, org.bukkit.entity.TNTPrimed.class, tnt -> {
            tnt.setFuseTicks(5);
            tnt.setSource(user);
        });

        // 4 lightning strikes spread around
        int[][] lOffsets = {{-3,0},{3,0},{0,-3},{0,3}};
        for (int[] o : lOffsets) {
            world.strikeLightning(center.clone().add(o[0], 0, o[1]));
        }

        // Warn the target team
        getTeam(teamName).ifPresent(team -> {
            for (GamePlayer gp : team.getMembers()) {
                gp.getPlayer().sendMessage("§4§l☢ NUCLEAR BOMB §7incoming from §c" + user.getName() + "§7!");
                gp.getPlayer().showTitle(net.kyori.adventure.title.Title.title(
                        net.kyori.adventure.text.Component.text("§4§l☢ NUKE INCOMING"),
                        net.kyori.adventure.text.Component.text("§cRun!"),
                        net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(100),
                                java.time.Duration.ofMillis(3000),
                                java.time.Duration.ofMillis(500))));
            }
        });

        broadcast("§4§l☢ §c" + user.getName() + " §7launched a §4§lNuclear Bomb §7at §c"
                + teamName.toUpperCase() + "§7's base!");
    }

    /** PDC key stored on the thrown Trident entity to track its owner UUID. */
    public static final org.bukkit.NamespacedKey DAGGER_OWNER_KEY =
            new org.bukkit.NamespacedKey("obbedwars", "dagger_owner");

    /** Creates a Dagger item — right-click to throw, returns on hit. */
    public static ItemStack makeDagger() {
        ItemStack item = new ItemStack(Material.IRON_SWORD, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§5§lDagger")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to throw. Returns on hit.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§85 second cooldown.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "dagger"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.EPIC);
    }

    public static boolean isDagger(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SWORD) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "dagger"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // -------------------------------------------------------------------------
    // Quick Crafting Table + Templates
    // -------------------------------------------------------------------------

    public static final String QCT_GUI_TITLE = "§6§lQuick Crafting Table";
    /** Slot in the QCT GUI where the template is placed. */
    public static final int QCT_TEMPLATE_SLOT = 11;
    /** Slot showing the recipe info. */
    public static final int QCT_INFO_SLOT     = 13;
    /** Slot for the craft button. */
    public static final int QCT_CRAFT_SLOT    = 15;

    public static ItemStack makeQuickCraftingTable() {
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§6§lQuick Crafting Table")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click to open. Place a template and craft.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "quick_crafting_table"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.RARE);
    }

    public static boolean isQuickCraftingTable(ItemStack item) {
        if (item == null || item.getType() != Material.CRAFTING_TABLE) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "quick_crafting_table"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public static ItemStack makeTemplate(String id) {
        CraftTemplate tmpl = TEMPLATES.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        ItemStack item = new ItemStack(Material.PAPER, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(tmpl.displayName() + " Template")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Cost: " + tmpl.costDesc())
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8Place in Quick Crafting Table to use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "craft_template"),
                org.bukkit.persistence.PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return applyRarity(item, tmpl.rarity());
    }

    public static String getTemplateId(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return null;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey("obbedwars", "craft_template"),
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    /** Opens the Quick Crafting Table GUI for a player. */
    public static void openQctGui(Player player) {
        org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, 27,
                net.kyori.adventure.text.Component.text(QCT_GUI_TITLE));

        // Fill background with gray glass
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta fm = filler.getItemMeta();
        fm.displayName(net.kyori.adventure.text.Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = 0; i < 27; i++) gui.setItem(i, filler);

        // Template slot — leave empty for player to place
        gui.setItem(QCT_TEMPLATE_SLOT, null);

        // Info slot
        ItemStack info = new ItemStack(Material.BOOK);
        org.bukkit.inventory.meta.ItemMeta im = info.getItemMeta();
        im.displayName(net.kyori.adventure.text.Component.text("§eHow to use")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        im.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Place a §6Crafting Template")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7in the left slot, then click")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§aCraft §7to receive your items.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        info.setItemMeta(im);
        gui.setItem(QCT_INFO_SLOT, info);

        // Craft button
        gui.setItem(QCT_CRAFT_SLOT, buildCraftButton(null));

        player.openInventory(gui);
    }

    public static ItemStack buildCraftButton(@Nullable CraftTemplate tmpl) {
        ItemStack btn = new ItemStack(tmpl == null ? Material.RED_STAINED_GLASS_PANE : Material.GREEN_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta m = btn.getItemMeta();
        if (tmpl == null) {
            m.displayName(net.kyori.adventure.text.Component.text("§cNo template placed")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        } else {
            m.displayName(net.kyori.adventure.text.Component.text("§aCraft: " + tmpl.displayName())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            m.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text("§7Cost: " + tmpl.costDesc())
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                    net.kyori.adventure.text.Component.text("§eClick to craft!")
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        }
        btn.setItemMeta(m);
        return btn;
    }

    /** Creates a Bed Trap item. */
    public static ItemStack makeBedTrap() {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("§c§lBed Trap")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§7Right-click a bed to place a trap.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7Anyone else who tries to mine it")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§7gets launched 10 blocks up & 6 back.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("§8One-time use.")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "bed_trap"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return applyRarity(item, Rarity.UNCOMMON);
    }

    public static boolean isBedTrap(ItemStack item) {
        if (item == null || item.getType() != Material.TRIPWIRE_HOOK) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("obbedwars", "bed_trap"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /**
     * Places a bed trap on the bed nearest to the player's look target.
     * @return the team name the trap was placed on, or null if no bed found
     */
    public String placeBedTrap(Player player, Block bedBlock) {
        String bedTeam = getBedTeamAt(bedBlock.getLocation());
        if (bedTeam == null) return null;
        if (bedHealth.getOrDefault(bedTeam.toLowerCase(), 0) <= 0) return null;
        bedTraps.put(bedTeam.toLowerCase(), player.getUniqueId());
        return bedTeam;
    }

    /**
     * Activates a Bed Anchor for the player's team.
     * @return false if the team already has an anchor active or has no bed
     */
    public boolean placeBedAnchor(Player player) {
        String teamName = getPlayerTeamName(player);
        if (teamName == null) return false;
        if (bedHealth.getOrDefault(teamName.toLowerCase(), 0) <= 0) return false;
        bedAnchors.merge(teamName.toLowerCase(), 1, Integer::sum);
        updateScoreboard();
        return true;
    }

    /**
     * Attempts to heal the bed of the given player's team by 1 step.
     * @return true if healed, false if already at full health or no bed
     */
    public boolean healBed(Player player) {
        String teamName = getPlayerTeamName(player);
        if (teamName == null) return false;
        int health = bedHealth.getOrDefault(teamName.toLowerCase(), 0);
        if (health <= 0 || health >= BED_MAX_HEALTH) return false;
        health++;
        bedHealth.put(teamName.toLowerCase(), health);
        updateScoreboard();
        updateBedArmorStand(teamName);
        return true;
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
    public Map<String, Integer> getBedHealth() { return bedHealth; }
    public boolean isBedAlive(String teamName) { return bedHealth.getOrDefault(teamName.toLowerCase(), 0) > 0; }
    public boolean isSpectator(UUID playerId) { return spectatorPlayers.contains(playerId); }
}
