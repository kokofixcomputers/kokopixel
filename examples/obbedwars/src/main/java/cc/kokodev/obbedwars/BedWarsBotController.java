package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.api.bot.BotActions;
import cc.kokodev.kokopixel.api.bot.BotController;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.bot.BotSenses;
import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameTeam;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public class BedWarsBotController implements BotController {

    // =========================================================================
    // BedKnowledge
    // =========================================================================

    static class BedKnowledge {
        final String teamName;
        final Location bedLoc;
        final Set<Material> outerLayers = new LinkedHashSet<>();
        final Set<Material> innerLayers = new LinkedHashSet<>();
        boolean outerScanned = false;
        boolean innerScanned = false;

        static final Set<Material> BED_MATERIALS = EnumSet.of(
            Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED,
            Material.LIGHT_BLUE_BED, Material.YELLOW_BED, Material.LIME_BED,
            Material.PINK_BED, Material.GRAY_BED, Material.LIGHT_GRAY_BED,
            Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
            Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED,
            Material.BLACK_BED
        );

        BedKnowledge(String teamName, Location bedLoc) {
            this.teamName = teamName;
            this.bedLoc = bedLoc.clone();
        }

        void observe(BotHandle bot) {
            double dist = bot.getSenses().distanceTo(bedLoc);
            int radius = dist < 3 ? 2 : 4;
            org.bukkit.World world = bot.getWorld();
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        Block b = world.getBlockAt(
                            bedLoc.getBlockX() + x,
                            bedLoc.getBlockY() + y,
                            bedLoc.getBlockZ() + z);
                        Material mat = b.getType();
                        if (mat.isAir() || BED_MATERIALS.contains(mat)) continue;
                        if (dist < 3) innerLayers.add(mat);
                        else outerLayers.add(mat);
                    }
                }
            }
            if (dist < 8) outerScanned = true;
            if (dist < 3) innerScanned = true;
        }

        Material hardestKnownBlock() {
            Material hardest = null;
            int hardestTier = -1;
            for (Material m : outerLayers) {
                int tier = pickaxeTierIndex(m);
                if (tier > hardestTier) { hardestTier = tier; hardest = m; }
            }
            for (Material m : innerLayers) {
                int tier = pickaxeTierIndex(m);
                if (tier > hardestTier) { hardestTier = tier; hardest = m; }
            }
            return hardest;
        }

        private int pickaxeTierIndex(Material m) {
            Material pick = MIN_PICKAXE.getOrDefault(m, Material.WOODEN_PICKAXE);
            return PICKAXE_TIERS.indexOf(pick);
        }

        Material requiredPickaxe() {
            Material hardest = hardestKnownBlock();
            if (hardest == null) return Material.WOODEN_PICKAXE;
            return MIN_PICKAXE.getOrDefault(hardest, Material.WOODEN_PICKAXE);
        }

        boolean hasUnknownInner() { return outerScanned && !innerScanned; }
        boolean isCompletelyUnprotected() { return innerScanned && outerLayers.isEmpty() && innerLayers.isEmpty(); }
    }

    // =========================================================================
    // Goal enum
    // =========================================================================

    enum Goal {
        IDLE, SCOUT_BED, GET_TOOL, GET_GEAR, DEFEND_OWN_BED,
        APPROACH_BED, CLEAR_DEFENCE, BREAK_BED,
        HUNT_PLAYER, FIGHT, VOID_PUSH, AIRBORNE
    }

    // =========================================================================
    // Static constants
    // =========================================================================

    static final Map<Material, Material> MIN_PICKAXE = new EnumMap<>(Material.class);
    static final List<Material> PICKAXE_TIERS = List.of(
        Material.WOODEN_PICKAXE, Material.STONE_PICKAXE,
        Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE
    );
    static final List<Material> BRIDGE_PRIORITY = List.of(
        Material.OAK_LOG, Material.COBBLESTONE, Material.OAK_PLANKS,
        Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
        Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.DIRT,
        Material.GRAVEL, Material.SAND
    );

    static {
        for (Material m : new Material[]{
            Material.COBBLESTONE, Material.STONE, Material.STONE_BRICKS,
            Material.MOSSY_COBBLESTONE, Material.SANDSTONE, Material.IRON_BARS,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE
        }) MIN_PICKAXE.put(m, Material.STONE_PICKAXE);

        for (Material m : new Material[]{
            Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.LAPIS_BLOCK,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.REDSTONE_BLOCK
        }) MIN_PICKAXE.put(m, Material.IRON_PICKAXE);

        for (Material m : new Material[]{
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.ANCIENT_DEBRIS,
            Material.ENCHANTING_TABLE, Material.ENDER_CHEST
        }) MIN_PICKAXE.put(m, Material.DIAMOND_PICKAXE);
    }

    private static final double MOVE_SPEED            = 0.25;
    private static final double SPRINT_SPEED          = 0.35;
    private static final int    RESPAWN_TICKS         = 50;
    private static final int    AIRBORNE_TIMEOUT      = 30;
    private static final int    FIREBALL_COOLDOWN     = 40;
    private static final double FIREBALL_MIN_DIST     = 5.0;
    private static final double FIREBALL_MAX_DIST     = 14.0;
    // FIX: reduced from 4.0 — only fight back if enemy is very close AND actively threatening
    private static final double CLOSE_ENEMY_DIST      = 3.0;
    private static final double FIGHT_RETREAT_DIST    = 10.0;
    private static final double VELOCITY_SPIKE_SQ     = 1.8 * 1.8;
    private static final int    VOID_Y_THRESHOLD      = 5;
    private static final double VOID_PUSH_RANGE       = 1.5;
    private static final int    PATIENCE_KILL_THRESHOLD = 3;
    private static final int    STUCK_THRESHOLD       = 40;
    // FIX: how many successful breakBlock() calls are needed to destroy the bed
    private static final int    BED_BREAKS_REQUIRED   = 3;

    // =========================================================================
    // Inner classes
    // =========================================================================

    private static class ThreatRecord {
        int killsAgainstBot = 0;
        boolean inMyWay     = false;
        int lastThreatTick  = 0;
    }

    private static class EnemyProfile {
        int armorTier  = 0;
        int swordTier  = 0;
        boolean hasBeatWithLowerGear   = false;
        int gearTierWhenWeBeatThem     = 0;
        boolean voidKnockSucceeded     = false;

        void observe(Player p) {
            armorTier = calcArmorTier(p);
            swordTier = calcSwordTier(p);
        }

        private static int calcArmorTier(Player p) {
            var helm = p.getInventory().getHelmet();
            if (helm == null) return 0;
            String n = helm.getType().name();
            if (n.startsWith("NETHERITE")) return 4;
            if (n.startsWith("DIAMOND"))   return 3;
            if (n.startsWith("IRON"))      return 2;
            return 1;
        }

        private static int calcSwordTier(Player p) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) return 0;
            String n = hand.getType().name();
            if (n.equals("NETHERITE_SWORD")) return 4;
            if (n.equals("DIAMOND_SWORD"))   return 3;
            if (n.endsWith("_SWORD"))        return n.startsWith("IRON") ? 2 : 1;
            return 0;
        }

        static int calcBotGearTier(BotHandle bot) {
            var helm = bot.getBukkitPlayer().getInventory().getHelmet();
            if (helm == null) return 0;
            String n = helm.getType().name();
            if (n.startsWith("NETHERITE")) return 4;
            if (n.startsWith("DIAMOND"))   return 3;
            if (n.startsWith("IRON"))      return 2;
            return 1;
        }
    }

    // =========================================================================
    // Instance state
    // =========================================================================

    private final JavaPlugin plugin;
    private BotDebugger debug;

    private final Deque<Goal> goalStack = new ArrayDeque<>();
    private String       targetTeam         = null;
    private BedKnowledge targetKnowledge    = null;
    private Block        currentBreakTarget = null;
    private Player       fightTarget        = null;

    private int    deadTimer     = 0;
    private int    airborneTimer = 0;
    private int    fireballCd    = 0;
    private Vector lastVelocity  = new Vector(0, 0, 0);

    // Stuck detection
    private int      stuckTimer   = 0;
    private Location lastStuckPos = null;

    // FIX: track own-bed break progress so we keep swinging across ticks
    private Block ownBedBreakBlock  = null;
    private int   ownBedBreakHits   = 0;

    // FIX: track enemy bed break progress (bed has 3 HP)
    private int bedBreaksLanded = 0;

    private final Map<UUID, ThreatRecord> threats       = new HashMap<>();
    private final Map<UUID, EnemyProfile> enemyProfiles = new HashMap<>();

    public BedWarsBotController(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // BotController lifecycle
    // =========================================================================

    @Override
    public void onStart(BotHandle bot, GameInstance game) {
        debug = new BotDebugger(bot.getName(), game);
        goalStack.clear();
        pickTargetTeam(bot, game);
        // FIX: GET_TOOL runs FIRST (bottom of stack = last pushed),
        // then SCOUT_BED once we have tools/blocks.
        // Stack after push (top→bottom): GET_TOOL | SCOUT_BED
        pushGoal(Goal.SCOUT_BED);
        pushGoal(Goal.GET_TOOL);
        debug.event("Spawned! Target: " + (targetTeam != null ? targetTeam : "none") + ". Getting tools first.");
    }

    @Override
    public void onTick(BotHandle bot, GameInstance game) {
        if (fireballCd > 0) fireballCd--;
        tickInterrupts(bot, game);
        executeCurrentGoal(bot, game);
    }

    @Override
    public boolean onDeath(BotHandle bot, GameInstance game) {
        var dmg = bot.getBukkitPlayer().getLastDamageCause();
        if (dmg instanceof org.bukkit.event.entity.EntityDamageByEntityEvent edbe) {
            Entity damager = edbe.getDamager();
            if (damager instanceof Player kp) {
                recordKilledBy(kp.getUniqueId());
                threats.computeIfAbsent(kp.getUniqueId(), k -> new ThreatRecord()).inMyWay = false;
            } else if (damager instanceof org.bukkit.entity.Projectile proj
                    && proj.getShooter() instanceof Player kp) {
                recordKilledBy(kp.getUniqueId());
            }
        }

        goalStack.clear();
        fightTarget        = null;
        currentBreakTarget = null;
        ownBedBreakBlock   = null;
        ownBedBreakHits    = 0;
        bedBreaksLanded    = 0;
        stuckTimer         = 0;
        lastStuckPos       = null;
        targetKnowledge    = null;
        deadTimer          = RESPAWN_TICKS;

        Location respawnLoc = findOwnBedLocationRaw(bot, game);
        bot.teleport(respawnLoc != null
            ? respawnLoc.clone().add(0, 1, 0)
            : game.getWorld().getSpawnLocation());

        // Stack (top→bottom): IDLE | GET_TOOL | SCOUT_BED
        pushGoal(Goal.SCOUT_BED);
        pushGoal(Goal.GET_TOOL);
        pushGoal(Goal.IDLE);

        debug.warn("Died! Respawning. Will restock then head out.");
        return true;
    }

    @Override
    public void onStop(BotHandle bot, GameInstance game) {
        goalStack.clear();
    }

    // =========================================================================
    // Interrupt layer
    // FIX: never interrupt GET_TOOL or GET_GEAR with FIGHT — bot is on own island
    // FIX: require enemy to be actively attacking (armed + facing) AND very close
    // =========================================================================

    private void tickInterrupts(BotHandle bot, GameInstance game) {
        Goal cur = currentGoal();

        // Update enemy profiles passively
        for (GamePlayer gp : game.getPlayers()) {
            if (!gp.isAlive() || bot.getSenses().isAlly(gp.getPlayer(), game)) continue;
            if (bot.getSenses().hasLineOfSight(gp.getPlayer())) {
                profileOf(gp.getPlayer()).observe(gp.getPlayer());
            }
        }

        if (cur == Goal.IDLE || cur == Goal.AIRBORNE) return;

        // 1. Velocity spike → AIRBORNE
        Vector vel = bot.getBukkitPlayer().getVelocity();
        double horizSq = vel.getX() * vel.getX() + vel.getZ() * vel.getZ();
        if (horizSq > VELOCITY_SPIKE_SQ) {
            airborneTimer = AIRBORNE_TIMEOUT;
            pushGoal(Goal.AIRBORNE);
            lastVelocity = vel.clone();
            return;
        }
        lastVelocity = vel.clone();

        // 2. Close enemy → FIGHT
        // Only react if on the way to the bed AND enemy is actively attacking (not just nearby)
        boolean onOwnIsland = (cur == Goal.GET_TOOL || cur == Goal.GET_GEAR || cur == Goal.DEFEND_OWN_BED);
        if (!onOwnIsland && cur != Goal.FIGHT && cur != Goal.HUNT_PLAYER) {
            Optional<GamePlayer> nearEnemy = bot.getSenses().getNearestEnemy(CLOSE_ENEMY_DIST, game);
            if (nearEnemy.isPresent()) {
                Player enemy = nearEnemy.get().getPlayer();
                // Only fight back if the enemy has actually hit us recently (last damage cause)
                // or if they're within 1.5 blocks (melee range) — not just nearby with a sword
                boolean activelyAttacking = false;
                var lastDmg = bot.getBukkitPlayer().getLastDamageCause();
                if (lastDmg instanceof org.bukkit.event.entity.EntityDamageByEntityEvent edbe) {
                    Entity attacker = edbe.getDamager();
                    if (attacker != null && attacker.getUniqueId().equals(enemy.getUniqueId())) {
                        activelyAttacking = true;
                    }
                }
                double dist = bot.getSenses().distanceTo(enemy.getLocation());
                if (activelyAttacking || dist < 1.5) {
                    ThreatRecord rec = threats.computeIfAbsent(enemy.getUniqueId(), k -> new ThreatRecord());
                    rec.inMyWay = true;
                    if (needsFireballAgainst(bot, enemy)) {
                        if (cur != Goal.GET_TOOL) pushGoal(Goal.GET_TOOL);
                    } else {
                        fightTarget = enemy;
                        if (cur != Goal.FIGHT) pushGoal(Goal.FIGHT);
                    }
                    return;
                }
            }
        }

        // 3. Ensure target team
        if (targetTeam == null) pickTargetTeam(bot, game);

        // 4. Passive bed scan when nearby
        if (targetKnowledge != null && bot.getSenses().distanceTo(targetKnowledge.bedLoc) <= 8) {
            targetKnowledge.observe(bot);
        }
    }

    // =========================================================================
    // Goal stack helpers
    // =========================================================================

    private void pushGoal(Goal g) {
        if (goalStack.peekFirst() == g) return;
        goalStack.addFirst(g);
        if (debug != null) debug.goal(goalLabel(g));
    }

    private void popGoal() { goalStack.pollFirst(); }

    private Goal currentGoal() {
        Goal g = goalStack.peekFirst();
        return g != null ? g : Goal.IDLE;
    }

    private String goalLabel(Goal g) {
        String team  = targetTeam != null ? targetTeam : "enemy";
        String fight = fightTarget != null ? fightTarget.getName() : "someone";
        return switch (g) {
            case IDLE           -> "Waiting to respawn...";
            case SCOUT_BED      -> "Scouting " + team + "'s bed defences";
            case GET_TOOL       -> "Going to my bed for tools/blocks";
            case GET_GEAR       -> "Going to my bed for better gear";
            case DEFEND_OWN_BED -> "Placing obsidian around my bed";
            case APPROACH_BED   -> "Moving toward " + team + "'s bed";
            case CLEAR_DEFENCE  -> "Breaking defence around " + team + "'s bed";
            case BREAK_BED      -> "Breaking " + team + "'s bed! (" + bedBreaksLanded + "/" + BED_BREAKS_REQUIRED + ")";
            case HUNT_PLAYER    -> "Hunting " + team + " players";
            case FIGHT          -> "Fighting " + fight + " — clearing the path!";
            case VOID_PUSH      -> "Knocking " + fight + " into the void";
            case AIRBORNE       -> "Launched! Waiting to land...";
        };
    }

    // =========================================================================
    // Goal executor dispatcher
    // =========================================================================

    private void executeCurrentGoal(BotHandle bot, GameInstance game) {
        switch (currentGoal()) {
            case IDLE           -> executeGoal_IDLE(bot, game);
            case SCOUT_BED      -> executeGoal_SCOUT_BED(bot, game);
            case GET_TOOL       -> executeGoal_GET_TOOL(bot, game);
            case GET_GEAR       -> executeGoal_GET_GEAR(bot, game);
            case DEFEND_OWN_BED -> executeGoal_DEFEND_OWN_BED(bot, game);
            case APPROACH_BED   -> executeGoal_APPROACH_BED(bot, game);
            case CLEAR_DEFENCE  -> executeGoal_CLEAR_DEFENCE(bot, game);
            case BREAK_BED      -> executeGoal_BREAK_BED(bot, game);
            case HUNT_PLAYER    -> executeGoal_HUNT_PLAYER(bot, game);
            case FIGHT          -> executeGoal_FIGHT(bot, game);
            case VOID_PUSH      -> executeGoal_VOID_PUSH(bot, game);
            case AIRBORNE       -> executeGoal_AIRBORNE(bot, game);
        }
    }

    // =========================================================================
    // IDLE
    // =========================================================================

    private void executeGoal_IDLE(BotHandle bot, GameInstance game) {
        if (deadTimer > 0) { deadTimer--; return; }
        popGoal(); // reveals GET_TOOL underneath
        debug.think("Respawn timer done. Heading out.");
    }

    // =========================================================================
    // SCOUT_BED
    // =========================================================================

    private void executeGoal_SCOUT_BED(BotHandle bot, GameInstance game) {
        if (targetTeam == null) {
            pickTargetTeam(bot, game);
            if (targetTeam == null) { popGoal(); return; }
        }

        if (targetKnowledge == null) {
            Location bedLoc = findTeamBedLocation(bot, game, targetTeam);
            if (bedLoc == null) {
                popGoal();
                pushGoal(Goal.HUNT_PLAYER);
                return;
            }
            targetKnowledge = new BedKnowledge(targetTeam, bedLoc);
        }

        double dist = bot.getSenses().distanceTo(targetKnowledge.bedLoc);

        // FIX: don't try to walk toward bed until we actually have bridge blocks
        if (dist > 8 && bestBridgeMaterial(bot) == null) {
            debug.warn("Need blocks to scout. Getting tools first.");
            pushGoal(Goal.GET_TOOL);
            return;
        }

        if (dist > 8) {
            moveToward(bot, targetKnowledge.bedLoc, true);
            return;
        }

        targetKnowledge.observe(bot);
        popGoal();

        Material neededPick = targetKnowledge.requiredPickaxe();
        boolean hasTool   = hasPickaxe(bot, neededPick);
        boolean hasBlocks = bestBridgeMaterial(bot) != null;

        debug.think("Scout done. neededPick=" + neededPick + " hasTool=" + hasTool + " hasBlocks=" + hasBlocks);

        pushGoal(Goal.APPROACH_BED);
        if (!hasTool || !hasBlocks) {
            pushGoal(Goal.GET_TOOL);
        }
    }

    // =========================================================================
    // GET_TOOL
    // FIX 1: try simulateCrafting FIRST with existing inventory before walking to bed
    // FIX 2: keep breaking own bed across ticks until breakBlock() returns true
    // =========================================================================

    private void executeGoal_GET_TOOL(BotHandle bot, GameInstance game) {
        Material needed = targetKnowledge != null
            ? targetKnowledge.requiredPickaxe()
            : Material.WOODEN_PICKAXE;

        // FIX: try crafting from existing inventory first — maybe we already have cobblestone etc.
        simulateCrafting(bot);

        boolean alreadyHasTool   = hasPickaxe(bot, needed);
        boolean alreadyHasBlocks = bestBridgeMaterial(bot) != null;

        if (alreadyHasTool && alreadyHasBlocks) {
            debug.think("Already have what I need (crafted or pre-existing). Skipping bed grind.");
            ownBedBreakBlock = null;
            ownBedBreakHits  = 0;
            popGoal();
            return;
        }

        Location ownBed = findOwnBedLocationRaw(bot, game);
        if (ownBed == null) {
            debug.warn("Own bed gone and crafting wasn't enough. Moving on.");
            popGoal();
            return;
        }

        double dist = bot.getSenses().distanceTo(ownBed);

        if (dist > 2.5) {
            moveToward(bot, ownBed, true);
            return;
        }

        // FIX: cache the bed block so we keep targeting the same one across ticks
        if (ownBedBreakBlock == null || ownBedBreakBlock.getType().isAir()
                || !BedKnowledge.BED_MATERIALS.contains(ownBedBreakBlock.getType())) {
            ownBedBreakBlock = findBedBlock(bot, ownBed, 3);
        }

        if (ownBedBreakBlock == null) {
            // Bed block not found — it was already broken; loot is on the ground
            simulateCrafting(bot);
            logInventory(bot);
            ownBedBreakHits = 0;
            popGoal();
            return;
        }

        // Keep swinging at own bed every tick until breakBlock() returns true
        bot.getActions().lookAt(ownBedBreakBlock.getLocation().add(0.5, 0.5, 0.5), 30f);
        equipPickaxe(bot, Material.WOODEN_PICKAXE);
        boolean broke = bot.getActions().breakBlock(ownBedBreakBlock);
        bot.getActions().swingMainHand();

        if (broke) {
            ownBedBreakHits++;
            debug.event("Own bed hit #" + ownBedBreakHits + " broken=" + broke);
            simulateCrafting(bot);
            logInventory(bot);

            // FIX: check again if we now satisfy requirements — if yes, done
            boolean nowHasTool   = hasPickaxe(bot, needed);
            boolean nowHasBlocks = bestBridgeMaterial(bot) != null;

            if (nowHasTool && nowHasBlocks) {
                debug.event("Got what I need! tool=" + needed + " blocks=OK");
                ownBedBreakBlock = null;
                ownBedBreakHits  = 0;
                popGoal();
            }
            // else: bed block may still exist (3 HP) — loop will find it again next tick
        }
        // If broke == false: keep hitting next tick (no popGoal, no return early)
    }

    // =========================================================================
    // GET_GEAR
    // =========================================================================

    private void executeGoal_GET_GEAR(BotHandle bot, GameInstance game) {
        Location ownBed = findOwnBedLocationRaw(bot, game);
        if (ownBed == null) { popGoal(); return; }

        double dist = bot.getSenses().distanceTo(ownBed);
        if (dist > 2.5) { moveToward(bot, ownBed, true); return; }

        if (ownBedBreakBlock == null || ownBedBreakBlock.getType().isAir()
                || !BedKnowledge.BED_MATERIALS.contains(ownBedBreakBlock.getType())) {
            ownBedBreakBlock = findBedBlock(bot, ownBed, 3);
        }

        if (ownBedBreakBlock == null) { equipBestArmor(bot); popGoal(); return; }

        bot.getActions().lookAt(ownBedBreakBlock.getLocation().add(0.5, 0.5, 0.5), 30f);
        equipPickaxe(bot, Material.WOODEN_PICKAXE);
        boolean broke = bot.getActions().breakBlock(ownBedBreakBlock);
        bot.getActions().swingMainHand();

        if (broke) {
            simulateCrafting(bot);
            equipBestArmor(bot);
            logInventory(bot);
            debug.event("Got gear from own bed.");
            ownBedBreakBlock = null;
            ownBedBreakHits  = 0;
            popGoal();
        }
    }

    // =========================================================================
    // DEFEND_OWN_BED
    // =========================================================================

    private void executeGoal_DEFEND_OWN_BED(BotHandle bot, GameInstance game) {
        Location ownBed = findOwnBedLocationRaw(bot, game);
        if (ownBed == null) { popGoal(); return; }

        if (bot.getSenses().distanceTo(ownBed) > 3) { moveToward(bot, ownBed, false); return; }

        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            Block neighbor = ownBed.getBlock().getRelative(face);
            if (neighbor.getType().isAir()) {
                boolean placed = bot.getActions().placeBlock(neighbor, face.getOppositeFace(), Material.OBSIDIAN);
                if (placed) return;
            }
        }
        debug.think("Bed defended.");
        popGoal();
    }

    // =========================================================================
    // APPROACH_BED
    // =========================================================================

    private void executeGoal_APPROACH_BED(BotHandle bot, GameInstance game) {
        if (targetKnowledge == null) { popGoal(); return; }

        if (targetTeam != null) {
            Optional<GameTeam> teamOpt = game.getTeam(targetTeam);
            if (teamOpt.isEmpty() || teamOpt.get().isEliminated()) {
                targetTeam = null; targetKnowledge = null;
                popGoal(); pickTargetTeam(bot, game); return;
            }
        }

        // FIX: only gate on pickaxe if we have NO pickaxe at all — use whatever we have otherwise
        // (see CLEAR_DEFENCE fix — we'll use best available even if below ideal tier)
        if (!hasAnyPickaxe(bot)) {
            debug.think("No pickaxe at all. Getting tool before approaching.");
            pushGoal(Goal.GET_TOOL);
            return;
        }

        double dist = bot.getSenses().distanceTo(targetKnowledge.bedLoc);

        if (dist <= 4) {
            popGoal();
            Block defence = findNearestDefenceBlock(bot, targetKnowledge.bedLoc, 4);
            if (defence != null) {
                currentBreakTarget = defence;
                pushGoal(Goal.CLEAR_DEFENCE);
            } else {
                bedBreaksLanded = 0;
                pushGoal(Goal.BREAK_BED);
            }
            return;
        }

        tickStuckDetection(bot);
        if (stuckTimer > STUCK_THRESHOLD) {
            stuckTimer = 0; lastStuckPos = null;
            debug.warn("Stuck while approaching. Jumping.");
            bot.getActions().jump();
        }

        moveToward(bot, targetKnowledge.bedLoc, true);
    }

    // =========================================================================
    // CLEAR_DEFENCE
    // FIX: use best available pickaxe instead of requiring exact tier
    // =========================================================================

    private void executeGoal_CLEAR_DEFENCE(BotHandle bot, GameInstance game) {
        if (targetKnowledge == null) { popGoal(); return; }

        if (currentBreakTarget == null || currentBreakTarget.getType().isAir()) {
            currentBreakTarget = findNearestDefenceBlock(bot, targetKnowledge.bedLoc, 4);
            if (currentBreakTarget == null) {
                popGoal();
                bedBreaksLanded = 0;
                pushGoal(Goal.BREAK_BED);
                return;
            }
        }

        double distToBlock = bot.getLocation().distance(
            currentBreakTarget.getLocation().add(0.5, 0.5, 0.5));
        if (distToBlock > 4) {
            moveToward(bot, currentBreakTarget.getLocation().add(0.5, 0.5, 0.5), false);
            return;
        }

        // FIX: equip BEST available pickaxe regardless of whether it meets the ideal tier.
        // We'll break it slowly if needed — never retreat just because we lack the ideal pick.
        equipBestAvailablePickaxe(bot);

        bot.getActions().lookAt(currentBreakTarget.getLocation().add(0.5, 0.5, 0.5), 30f);
        boolean broken = bot.getActions().breakBlock(currentBreakTarget);
        bot.getActions().swingMainHand();

        if (broken) {
            debug.event("Broke defence block: " + currentBreakTarget.getType());
            currentBreakTarget = null;
        }
    }

    // =========================================================================
    // BREAK_BED
    // FIX: track break count — bed has BED_BREAKS_REQUIRED HP
    // =========================================================================

    private void executeGoal_BREAK_BED(BotHandle bot, GameInstance game) {
        if (targetKnowledge == null) { popGoal(); return; }

        Block bedBlock = findBedBlock(bot, targetKnowledge.bedLoc, 2);
        if (bedBlock == null) {
            debug.event(targetTeam + "'s bed is gone! Hunting players.");
            targetKnowledge = null; bedBreaksLanded = 0;
            popGoal(); pushGoal(Goal.HUNT_PLAYER);
            return;
        }

        double dist = bot.getLocation().distance(bedBlock.getLocation().add(0.5, 0.5, 0.5));
        if (dist > 3.5) {
            moveToward(bot, bedBlock.getLocation().add(0.5, 0.5, 0.5), false);
            return;
        }

        bot.getActions().lookAt(bedBlock.getLocation().add(0.5, 0.5, 0.5), 30f);

        // Use sword to break bed (fastest) — fist if no sword
        ItemStack sword = bestSword(bot);
        if (sword != null) bot.getBukkitPlayer().getInventory().setItemInMainHand(sword);

        boolean broke = bot.getActions().breakBlock(bedBlock);
        bot.getActions().swingMainHand();

        if (broke) {
            bedBreaksLanded++;
            debug.event("Bed hit " + bedBreaksLanded + "/" + BED_BREAKS_REQUIRED + " on " + targetTeam);

            if (bedBreaksLanded >= BED_BREAKS_REQUIRED || bedBlock.getType().isAir()) {
                // Bed is fully destroyed
                debug.event("BED DESTROYED: " + targetTeam + "!");
                targetKnowledge = null; bedBreaksLanded = 0;
                popGoal(); pushGoal(Goal.HUNT_PLAYER);
            }
            // else: keep swinging next tick — bed still has HP
        }
        // broke == false: keep swinging next tick
    }

    // =========================================================================
    // HUNT_PLAYER
    // =========================================================================

    private void executeGoal_HUNT_PLAYER(BotHandle bot, GameInstance game) {
        if (targetTeam != null) {
            Optional<GameTeam> teamOpt = game.getTeam(targetTeam);
            if (teamOpt.isEmpty() || teamOpt.get().isEliminated()) {
                targetTeam = null;
                pickTargetTeam(bot, game);
                if (targetTeam == null) { popGoal(); return; }
            }
        }

        Optional<GamePlayer> nearestEnemy = bot.getSenses().getNearestEnemy(64, game);
        if (nearestEnemy.isEmpty()) {
            debug.think("No enemies found to hunt.");
            return;
        }

        Player target = nearestEnemy.get().getPlayer();
        double dist   = bot.getSenses().distanceTo(target.getLocation());

        observeEnemy(bot, target);
        ItemStack weapon = chooseWeapon(bot, target);

        if (weapon != null && BedWarsGame.isFireball(weapon)
                && dist >= FIREBALL_MIN_DIST && dist <= FIREBALL_MAX_DIST
                && fireballCd == 0) {
            bot.getActions().lookAtInstant(target.getEyeLocation());
            launchFireball(bot, target, weapon);
            fireballCd = FIREBALL_COOLDOWN;
            return;
        }

        if (dist > 2.5) {
            moveToward(bot, target.getLocation(), true);
        } else {
            bot.getActions().lookAt(target.getLocation(), 30f);
            if (weapon != null) bot.getBukkitPlayer().getInventory().setItemInMainHand(weapon);
            bot.getActions().attack(target);
            bot.getActions().swingMainHand();
            // Actually deal damage — attack() only animates
            double dmg = calcMeleeDamage(weapon);
            target.damage(dmg, bot.getBukkitPlayer());
        }
    }

    // =========================================================================
    // FIGHT
    // =========================================================================

    private void executeGoal_FIGHT(BotHandle bot, GameInstance game) {
        if (fightTarget == null || !fightTarget.isOnline() || fightTarget.isDead()) {
            fightTarget = null; popGoal(); return;
        }

        double dist = bot.getSenses().distanceTo(fightTarget.getLocation());

        if (dist > FIGHT_RETREAT_DIST) {
            ThreatRecord rec = threats.get(fightTarget.getUniqueId());
            if (rec != null) rec.inMyWay = false;
            fightTarget = null;
            debug.think("Enemy retreated. Resuming objective.");
            popGoal();
            return;
        }

        ItemStack weapon = chooseWeapon(bot, fightTarget);
        observeEnemy(bot, fightTarget);

        if (weapon != null && BedWarsGame.isFireball(weapon)
                && dist >= FIREBALL_MIN_DIST && dist <= FIREBALL_MAX_DIST
                && fireballCd == 0) {
            bot.getActions().lookAtInstant(fightTarget.getEyeLocation());
            launchFireball(bot, fightTarget, weapon);
            fireballCd = FIREBALL_COOLDOWN;
            return;
        }

        if (isNearVoid(fightTarget.getLocation())) {
            pushGoal(Goal.VOID_PUSH);
            return;
        }

        if (dist > 2.5) {
            moveToward(bot, fightTarget.getLocation(), true);
        } else {
            bot.getActions().lookAt(fightTarget.getLocation(), 30f);
            if (weapon != null) bot.getBukkitPlayer().getInventory().setItemInMainHand(weapon);
            bot.getActions().attack(fightTarget);
            bot.getActions().swingMainHand();
            fightTarget.damage(calcMeleeDamage(weapon), bot.getBukkitPlayer());
        }
    }

    // =========================================================================
    // VOID_PUSH
    // =========================================================================

    private void executeGoal_VOID_PUSH(BotHandle bot, GameInstance game) {
        if (fightTarget == null || !fightTarget.isOnline() || fightTarget.isDead()) {
            fightTarget = null; popGoal(); return;
        }
        if (!isNearVoid(fightTarget.getLocation())) { popGoal(); return; }

        double dist = bot.getSenses().distanceTo(fightTarget.getLocation());
        bot.getActions().lookAt(fightTarget.getLocation(), 30f);

        if (dist > VOID_PUSH_RANGE) {
            moveToward(bot, fightTarget.getLocation(), true);
        } else {
            ItemStack sword = bestSword(bot);
            if (sword != null) bot.getBukkitPlayer().getInventory().setItemInMainHand(sword);
            bot.getActions().attack(fightTarget);
            bot.getActions().swingMainHand();
            if (fightTarget.isDead() || fightTarget.getLocation().getY() < VOID_Y_THRESHOLD) {
                EnemyProfile ep = enemyProfiles.get(fightTarget.getUniqueId());
                if (ep != null) ep.voidKnockSucceeded = true;
                debug.event("Void knocked " + fightTarget.getName() + "!");
                fightTarget = null; popGoal();
            }
        }
    }

    // =========================================================================
    // AIRBORNE
    // =========================================================================

    private void executeGoal_AIRBORNE(BotHandle bot, GameInstance game) {
        if (airborneTimer > 0) { airborneTimer--; return; }
        if (bot.getBukkitPlayer().isOnGround()) {
            debug.think("Landed. Resuming.");
            popGoal();
        } else {
            airborneTimer = 5;
        }
    }

    // =========================================================================
    // Movement — unified bridging + pathfind
    // =========================================================================

    private boolean moveToward(BotHandle bot, Location target, boolean sprint) {
        Location here = bot.getLocation();
        double dist = here.distance(target);
        if (dist < 1.0) return true;

        BotActions actions = bot.getActions();
        BotSenses  senses  = bot.getSenses();

        actions.setSprinting(sprint);
        actions.lookAt(target, 20f);

        Vector dir = target.toVector().subtract(here.toVector()).setY(0);
        if (dir.lengthSquared() < 0.0001) return true;
        dir.normalize();

        Location oneAhead   = here.clone().add(dir.clone().multiply(1.0));
        Block belowHere     = here.getWorld().getBlockAt(here.getBlockX(),       here.getBlockY() - 1, here.getBlockZ());
        Block belowAhead    = here.getWorld().getBlockAt(oneAhead.getBlockX(),   here.getBlockY() - 1, oneAhead.getBlockZ());
        Block atAhead       = here.getWorld().getBlockAt(oneAhead.getBlockX(),   here.getBlockY(),     oneAhead.getBlockZ());
        Block aboveAhead    = here.getWorld().getBlockAt(oneAhead.getBlockX(),   here.getBlockY() + 1, oneAhead.getBlockZ());

        boolean aheadIsGap   = belowAhead.getType().isAir() || !belowAhead.getType().isSolid();
        boolean aheadIsWall  = atAhead.getType().isSolid()  && !atAhead.getType().isAir();
        boolean belowMissing = belowHere.getType().isAir()  || !belowHere.getType().isSolid();

        Material bridgeMat = bestBridgeMaterial(bot);

        if (belowMissing && bridgeMat != null) {
            // Place a block at the current feet position (Y-1 is air — fill it)
            Block currentFeet = here.getWorld().getBlockAt(here.getBlockX(), here.getBlockY(), here.getBlockZ());
            actions.placeBlock(currentFeet, BlockFace.DOWN, bridgeMat);
        }

        if (aheadIsGap && bridgeMat != null) {
            // Place a block at the ahead position at feet level (Y-1 ahead)
            Block aheadFeetBlock = here.getWorld().getBlockAt(oneAhead.getBlockX(), here.getBlockY(), oneAhead.getBlockZ());
            actions.placeBlock(aheadFeetBlock, BlockFace.DOWN, bridgeMat);
            bot.move(dir.getX() * MOVE_SPEED, 0, dir.getZ() * MOVE_SPEED, senses.yawToward(target), 0f);
            return false;
        }

        if (aheadIsGap) {
            // No bridge blocks — can't cross
            debug.warn("Gap ahead, no bridge blocks. Can't cross.");
            return false;
        }

        if (aheadIsWall && aboveAhead.getType().isAir()) {
            actions.jump();
        }

        if (sprint) actions.pathfindTo(target, SPRINT_SPEED);
        else        actions.pathfindTo(target, MOVE_SPEED);
        return false;
    }

    // =========================================================================
    // Stuck detection
    // =========================================================================

    private void tickStuckDetection(BotHandle bot) {
        Location here = bot.getLocation();
        if (lastStuckPos == null) { lastStuckPos = here.clone(); stuckTimer = 0; return; }
        double moved = here.distanceSquared(lastStuckPos);
        if (moved < 0.05) stuckTimer++;
        else { stuckTimer = 0; lastStuckPos = here.clone(); }
    }

    // =========================================================================
    // Threat / patience helpers
    // =========================================================================

    private void recordKilledBy(UUID id) {
        threats.computeIfAbsent(id, k -> new ThreatRecord()).killsAgainstBot++;
    }

    private boolean needsFireballAgainst(BotHandle bot, Player p) {
        ThreatRecord rec = threats.get(p.getUniqueId());
        return rec != null && rec.killsAgainstBot >= PATIENCE_KILL_THRESHOLD && findFireballFor(bot) == null;
    }

    private boolean isArmed(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) return false;
        String name = hand.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE")
            || hand.getType() == Material.BOW
            || hand.getType() == Material.CROSSBOW
            || hand.getType() == Material.FIRE_CHARGE;
    }

    private boolean isFacingBot(Player p, BotHandle bot) {
        Vector toBot  = bot.getLocation().toVector().subtract(p.getEyeLocation().toVector()).normalize();
        Vector facing = p.getLocation().getDirection();
        return facing.dot(toBot) > Math.cos(Math.toRadians(60));
    }

    private boolean isNearVoid(Location loc) {
        if (loc.getY() < VOID_Y_THRESHOLD + 4) return true;
        org.bukkit.World world = loc.getWorld();
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block edge = world.getBlockAt(
                loc.getBlockX() + face.getModX(), loc.getBlockY() - 1, loc.getBlockZ() + face.getModZ());
            if (edge.getType().isAir()) return true;
        }
        return false;
    }

    // =========================================================================
    // Weapon / enemy profile helpers
    // =========================================================================

    private EnemyProfile profileOf(Player p) {
        return enemyProfiles.computeIfAbsent(p.getUniqueId(), k -> new EnemyProfile());
    }

    private void observeEnemy(BotHandle bot, Player p) {
        if (bot.getSenses().hasLineOfSight(p)) profileOf(p).observe(p);
    }

    /** Returns the melee damage this weapon deals (vanilla approximation, no enchants). */
    private double calcMeleeDamage(ItemStack weapon) {
        if (weapon == null) return 1.0; // fist
        String n = weapon.getType().name();
        if (n.equals("NETHERITE_SWORD")) return 8.0;
        if (n.equals("DIAMOND_SWORD"))   return 7.0;
        if (n.equals("IRON_SWORD"))      return 6.0;
        if (n.equals("STONE_SWORD"))     return 5.0;
        if (n.equals("WOODEN_SWORD") || n.equals("GOLDEN_SWORD")) return 4.0;
        if (n.endsWith("_AXE"))          return 7.0; // axes deal high damage
        return 1.0;
    }

    private void launchFireball(BotHandle bot, Player target, ItemStack fbItem) {
        org.bukkit.Location eye = bot.getLocation().clone().add(0, 1.62, 0);
        Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
        org.bukkit.entity.Fireball fb = eye.getWorld().spawn(eye, org.bukkit.entity.Fireball.class);
        fb.setDirection(dir);
        fb.setShooter(bot.getBukkitPlayer());
        fb.setIsIncendiary(false);
        fb.setYield(1.5f);
        fbItem.setAmount(fbItem.getAmount() - 1);
        bot.getWorld().playSound(bot.getLocation(), org.bukkit.Sound.ENTITY_GHAST_SHOOT, 0.5f, 1.2f);
    }

    private ItemStack chooseWeapon(BotHandle bot, Player target) {
        double dist = bot.getSenses().distanceTo(target.getLocation());
        ItemStack fb = findFireballFor(bot);
        if (fb != null && dist >= FIREBALL_MIN_DIST && dist <= FIREBALL_MAX_DIST && fireballCd == 0) return fb;
        return bestSword(bot);
    }

    private ItemStack findFireballFor(BotHandle bot) {
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item != null && BedWarsGame.isFireball(item)) return item;
        }
        return null;
    }

    // =========================================================================
    // Inventory / item helpers
    // =========================================================================

    private ItemStack bestSword(BotHandle bot) {
        ItemStack best = null; int bestTier = -1;
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item == null) continue;
            String n = item.getType().name(); int tier = -1;
            if (n.equals("NETHERITE_SWORD"))  tier = 4;
            else if (n.equals("DIAMOND_SWORD"))  tier = 3;
            else if (n.equals("IRON_SWORD"))     tier = 2;
            else if (n.endsWith("_SWORD"))       tier = 1;
            if (tier > bestTier) { bestTier = tier; best = item; }
        }
        return best;
    }

    private boolean hasDiamondSword(BotHandle bot) {
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item != null && (item.getType() == Material.DIAMOND_SWORD
                    || item.getType() == Material.NETHERITE_SWORD)) return true;
        }
        return false;
    }

    private boolean hasPickaxe(BotHandle bot, Material minTier) {
        int minIndex = PICKAXE_TIERS.indexOf(minTier);
        if (minIndex < 0) minIndex = 0;
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item == null) continue;
            int idx = PICKAXE_TIERS.indexOf(item.getType());
            if (idx >= minIndex) return true;
        }
        return false;
    }

    /** FIX: returns true if the bot has ANY pickaxe at all, regardless of tier. */
    private boolean hasAnyPickaxe(BotHandle bot) {
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item == null) continue;
            if (PICKAXE_TIERS.contains(item.getType())) return true;
        }
        return false;
    }

    private void equipPickaxe(BotHandle bot, Material minTier) {
        int minIndex = PICKAXE_TIERS.indexOf(minTier);
        if (minIndex < 0) minIndex = 0;
        ItemStack best = null; int bestIdx = -1;
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item == null) continue;
            int idx = PICKAXE_TIERS.indexOf(item.getType());
            if (idx >= minIndex && idx > bestIdx) { bestIdx = idx; best = item; }
        }
        if (best != null) bot.getBukkitPlayer().getInventory().setItemInMainHand(best);
    }

    /**
     * FIX: equip the best pickaxe we have regardless of whether it meets the ideal tier.
     * Used by CLEAR_DEFENCE so we don't abort just because we have a wooden pick on obsidian.
     */
    private void equipBestAvailablePickaxe(BotHandle bot) {
        ItemStack best = null; int bestIdx = -1;
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item == null) continue;
            int idx = PICKAXE_TIERS.indexOf(item.getType());
            if (idx >= 0 && idx > bestIdx) { bestIdx = idx; best = item; }
        }
        if (best != null) bot.getBukkitPlayer().getInventory().setItemInMainHand(best);
    }

    private Material bestBridgeMaterial(BotHandle bot) {
        for (Material mat : BRIDGE_PRIORITY) {
            if (bot.getBukkitPlayer().getInventory().contains(mat)) return mat;
        }
        return null;
    }

    private int countBridgeMaterial(BotHandle bot) {
        int total = 0;
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item == null) continue;
            if (BRIDGE_PRIORITY.contains(item.getType())) total += item.getAmount();
        }
        return total;
    }

    private void equipBestArmor(BotHandle bot) {
        var inv = bot.getBukkitPlayer().getInventory();
        ItemStack bestHelm = null; int bestTier = -1;
        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;
            String n = item.getType().name(); int tier = -1;
            if (n.endsWith("_HELMET")) {
                if (n.startsWith("NETHERITE"))  tier = 4;
                else if (n.startsWith("DIAMOND")) tier = 3;
                else if (n.startsWith("IRON"))    tier = 2;
                else tier = 1;
            }
            if (tier > bestTier) { bestTier = tier; bestHelm = item; }
        }
        if (bestHelm != null) inv.setHelmet(bestHelm);
    }

    private void simulateCrafting(BotHandle bot) {
        var inv = bot.getBukkitPlayer().getInventory();
        int planks = 0;
        for (Material log : new Material[]{
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG}) {
            int count = inv.all(log).values().stream().mapToInt(ItemStack::getAmount).sum();
            planks += count * 4;
            if (count > 0) inv.remove(log);
        }
        planks += inv.all(Material.OAK_PLANKS).values().stream().mapToInt(ItemStack::getAmount).sum();
        inv.remove(Material.OAK_PLANKS);
        if (planks >= 5) {
            inv.addItem(new ItemStack(Material.WOODEN_PICKAXE));
            planks -= 5;
        }
        if (planks > 0) inv.addItem(new ItemStack(Material.OAK_PLANKS, planks));
    }

    private void logInventory(BotHandle bot) {
        if (debug == null) return;
        StringBuilder sb = new StringBuilder("Inventory: ");
        for (ItemStack item : bot.getBukkitPlayer().getInventory().getContents()) {
            if (item != null) sb.append(item.getType()).append("x").append(item.getAmount()).append(" ");
        }
        debug.think(sb.toString().trim());
    }

    // =========================================================================
    // Location / block helpers
    // =========================================================================

    private void pickTargetTeam(BotHandle bot, GameInstance game) {
        Optional<GameTeam> ownTeam = bot.getSenses().getOwnTeam(game);
        for (GameTeam team : game.getTeams()) {
            if (ownTeam.isPresent() && team.getName().equalsIgnoreCase(ownTeam.get().getName())) continue;
            if (!team.isEliminated()) {
                targetTeam = team.getName().toLowerCase();
                targetKnowledge = null;
                debug.think("Targeting team: " + targetTeam);
                return;
            }
        }
        targetTeam = null;
    }

    private Location findTeamBedLocation(BotHandle bot, GameInstance game, String team) {
        if (game instanceof BedWarsGame bedGame) return bedGame.getBedLocations().get(team.toLowerCase());
        return null;
    }

    private Location findOwnBedLocationRaw(BotHandle bot, GameInstance game) {
        Optional<GameTeam> ownTeam = bot.getSenses().getOwnTeam(game);
        if (ownTeam.isEmpty()) return null;
        if (game instanceof BedWarsGame bedGame) return bedGame.getBedLocations().get(ownTeam.get().getName().toLowerCase());
        return null;
    }

    private Location findOwnBedLocation(BotHandle bot, GameInstance game) {
        return findOwnBedLocationRaw(bot, game);
    }

    private Block findBedBlock(BotHandle bot, Location near, int radius) {
        org.bukkit.World world = near.getWorld();
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    Block b = world.getBlockAt(near.getBlockX() + x, near.getBlockY() + y, near.getBlockZ() + z);
                    if (BedKnowledge.BED_MATERIALS.contains(b.getType())) return b;
                }
        return null;
    }

    private Block findNearestDefenceBlock(BotHandle bot, Location center, int radius) {
        org.bukkit.World world = center.getWorld();
        Block nearest = null; double nearestDist = Double.MAX_VALUE;
        Location botLoc = bot.getLocation();
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    Block b = world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                    Material mat = b.getType();
                    if (mat.isAir() || BedKnowledge.BED_MATERIALS.contains(mat) || !mat.isSolid()) continue;
                    double distToBed = b.getLocation().distanceSquared(center);
                    double distToBot = b.getLocation().distanceSquared(botLoc);
                    if (distToBot < distToBed) continue;
                    if (distToBed < nearestDist) { nearestDist = distToBed; nearest = b; }
                }
        return nearest;
    }
}