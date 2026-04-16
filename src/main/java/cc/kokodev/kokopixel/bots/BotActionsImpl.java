package cc.kokodev.kokopixel.bots;

import cc.kokodev.kokopixel.api.bot.BotActions;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Implements higher-level bot actions on top of {@link BotHandle}'s raw packet API.
 *
 * <p>Pathfinding uses a simple A* over integer block positions.
 * Block-breaking tracks per-block progress based on hardness and held tool.
 */
public class BotActionsImpl implements BotActions {

    private static final double EYE_HEIGHT = 1.62;
    // Maximum break ticks before a block is forcibly removed (safety cap)
    private static final int MAX_BREAK_TICKS = 200;

    private final BotHandle bot;
    private final BotSensesImpl senses;

    // Pathfinding state
    private List<Location> currentPath = new ArrayList<>();
    private Location lastPathTarget = null;
    private int pathStaleTimer = 0;

    // Block breaking state
    private Block currentBreakTarget = null;
    private int breakProgress = 0;

    // Locomotion state
    private boolean sprinting = false;
    private boolean sneaking = false;
    private boolean jumping = false;

    // Virtual inventory for tool calculations
    private List<ItemStack> inventory = new ArrayList<>();

    public BotActionsImpl(BotHandle bot, BotSensesImpl senses) {
        this.bot = bot;
        this.senses = senses;
    }

    // -------------------------------------------------------------------------
    // Locomotion
    // -------------------------------------------------------------------------

    @Override
    public boolean walkToward(Location target, double speed) {
        Location here = bot.getLocation();
        double dx = target.getX() - here.getX();
        double dz = target.getZ() - here.getZ();
        double distH = Math.sqrt(dx * dx + dz * dz);
        if (distH <= 0.4) return true;

        float yaw = senses.yawToward(target);
        double step = Math.min(speed, distH);
        double ratio = step / distH;
        bot.move(dx * ratio, 0, dz * ratio, yaw, 0);
        return false;
    }

    @Override
    public boolean pathfindTo(Location target, double speed, int maxNodes) {
        // Recalculate if target moved significantly or path is stale
        boolean needsRecalc = currentPath.isEmpty()
                || lastPathTarget == null
                || lastPathTarget.distanceSquared(target) > 4.0
                || ++pathStaleTimer > 40;

        if (needsRecalc) {
            currentPath = computeAStarPath(bot.getLocation(), target, maxNodes);
            lastPathTarget = target.clone();
            pathStaleTimer = 0;
        }

        if (currentPath.isEmpty()) return false;

        Location next = currentPath.get(0);
        Location here = bot.getLocation();

        // Jump if next waypoint is 1 block higher and we're on the ground
        if (next.getBlockY() > here.getBlockY() && senses.isOnGround()) {
            jump();
        }

        boolean arrived = walkToward(next, speed);
        if (arrived) currentPath.remove(0);

        // Final destination check
        return currentPath.isEmpty() && here.distanceSquared(target) <= 0.5;
    }

    @Override
    public void jump() {
        if (!senses.isOnGround()) return;
        // Move the bot upward by 0.5 blocks to simulate jump — the client sees
        // a smooth parabola because we broadcast teleport packets each tick
        jumping = true;
        bot.move(0, 0.5, 0, bot.getLocation().getYaw(), bot.getLocation().getPitch());
        // Reset flag next tick (caller must manage multi-tick arc if needed)
        jumping = false;
    }

    @Override
    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        bot.setSprinting(sprinting);
    }

    @Override
    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        bot.setSneaking(sneaking);
    }

    // -------------------------------------------------------------------------
    // Combat
    // -------------------------------------------------------------------------

    @Override
    public void attack(Player target) {
        lookAtInstant(target.getEyeLocation());
        swingMainHand();
    }

    @Override
    public void swingMainHand() {
        bot.swingMainHand();
    }

    // -------------------------------------------------------------------------
    // Block interaction
    // -------------------------------------------------------------------------

    @Override
    public boolean breakBlock(Block block) {
        if (!Objects.equals(block, currentBreakTarget)) {
            currentBreakTarget = block;
            breakProgress = 0;
        }

        // Face the block
        lookAtInstant(block.getLocation().add(0.5, 0.5, 0.5));
        swingMainHand();

        int totalTicks = breakTicks(block.getType());
        breakProgress++;

        if (breakProgress >= totalTicks || breakProgress >= MAX_BREAK_TICKS) {
            block.setType(Material.AIR);
            currentBreakTarget = null;
            breakProgress = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean placeBlock(Block against, BlockFace face, Material material) {
        Block target = against.getRelative(face);
        if (!target.getType().isAir()) return false;
        Location botEye = bot.getLocation().add(0, EYE_HEIGHT, 0);
        if (botEye.distance(target.getLocation().add(0.5, 0.5, 0.5)) > 4.5) return false;
        target.setType(material);
        lookAtInstant(against.getLocation().add(0.5, 0.5, 0.5));
        return true;
    }

    @Override
    public boolean bridgeStep(Material material) {
        Location here = bot.getLocation();
        Vector backward = here.getDirection().setY(0).normalize().multiply(-1);
        Location trailingFeet = here.clone().add(backward);
        Block floor = here.getWorld().getBlockAt(
                trailingFeet.getBlockX(), here.getBlockY() - 1, trailingFeet.getBlockZ());
        if (!floor.getType().isAir()) return false;
        // Place against the block at feet level directly behind us
        Block support = here.getWorld().getBlockAt(
                trailingFeet.getBlockX(), here.getBlockY(), trailingFeet.getBlockZ());
        return placeBlock(support, BlockFace.DOWN, material);
    }

    // -------------------------------------------------------------------------
    // Look
    // -------------------------------------------------------------------------

    @Override
    public boolean lookAt(Location target, float maxDegPerTick) {
        float targetYaw = senses.yawToward(target);
        float targetPitch = senses.pitchToward(target);
        float curYaw = bot.getLocation().getYaw();
        float curPitch = bot.getLocation().getPitch();

        float dyaw = wrapDeg(targetYaw - curYaw);
        float dpitch = targetPitch - curPitch;

        dyaw = clamp(dyaw, -maxDegPerTick, maxDegPerTick);
        dpitch = clamp(dpitch, -maxDegPerTick, maxDegPerTick);

        bot.setHeadRotation(curYaw + dyaw, curPitch + dpitch);

        return Math.abs(wrapDeg(targetYaw - (curYaw + dyaw))) < 1f
                && Math.abs(targetPitch - (curPitch + dpitch)) < 1f;
    }

    @Override
    public void lookAtInstant(Location target) {
        bot.setHeadRotation(senses.yawToward(target), senses.pitchToward(target));
    }

    // -------------------------------------------------------------------------
    // Inventory / tool
    // -------------------------------------------------------------------------

    @Override
    public ItemStack bestToolFor(Material blockMaterial) {
        // Prefer the first matching tool in inventory; fall back to bare hand
        for (ItemStack item : inventory) {
            if (item == null) continue;
            if (isSuitableTool(item.getType(), blockMaterial)) return item;
        }
        return null;
    }

    @Override
    public void setInventory(List<ItemStack> items) {
        this.inventory = new ArrayList<>(items);
    }

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    @Override public List<Location> getCurrentPath() { return Collections.unmodifiableList(currentPath); }
    @Override public void clearPath() { currentPath.clear(); lastPathTarget = null; }
    @Override public boolean isJumping() { return jumping; }
    @Override public boolean isSprinting() { return sprinting; }
    @Override public boolean isSneaking() { return sneaking; }

    // =========================================================================
    // A* Pathfinding
    // =========================================================================

    private static final BlockFace[] CARDINALS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /** Simple immutable 3-int coordinate for A* nodes. */
    private static final class Node {
        final int x, y, z;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Node n)) return false;
            return x == n.x && y == n.y && z == n.z;
        }
        @Override public int hashCode() { return Objects.hash(x, y, z); }
    }

    /**
     * Simple A* over integer block positions.
     * Returns a list of block-centre locations to walk through, or an empty list
     * if no path is found within {@code maxNodes} expansions.
     */
    private List<Location> computeAStarPath(Location from, Location to, int maxNodes) {
        org.bukkit.World world = from.getWorld();
        Node start = new Node(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        Node end   = new Node(to.getBlockX(),   to.getBlockY(),   to.getBlockZ());

        Map<Node, Node>   cameFrom = new HashMap<>();
        Map<Node, Double> gScore   = new HashMap<>();
        PriorityQueue<Map.Entry<Node, Double>> open =
                new PriorityQueue<>(Comparator.comparingDouble(Map.Entry::getValue));

        gScore.put(start, 0.0);
        open.offer(Map.entry(start, heuristic(start, end)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.poll().getKey();
            expanded++;

            if (current.equals(end)) {
                return reconstructPath(cameFrom, current, world);
            }

            double g = gScore.getOrDefault(current, Double.MAX_VALUE);

            for (BlockFace face : CARDINALS) {
                int nx = current.x + face.getModX();
                int ny = current.y;
                int nz = current.z + face.getModZ();

                Block feet = world.getBlockAt(nx, ny, nz);
                Block head = world.getBlockAt(nx, ny + 1, nz);

                if (!isPassable(feet) || !isPassable(head)) {
                    // Try stepping up one block
                    if (isPassable(world.getBlockAt(nx, ny + 1, nz))
                            && isPassable(world.getBlockAt(nx, ny + 2, nz))
                            && isSolid(world.getBlockAt(nx, ny, nz))) {
                        ny += 1;
                    } else {
                        continue;
                    }
                }

                // Drop if no floor
                if (isPassable(world.getBlockAt(nx, ny - 1, nz))) {
                    int fall = 0;
                    while (fall < 3 && isPassable(world.getBlockAt(nx, ny - 1 - fall, nz))) fall++;
                    ny -= fall;
                    if (ny < 0) continue;
                }

                Node neighbour = new Node(nx, ny, nz);
                double ng = g + 1.0 + (ny > current.y ? 0.5 : 0);
                if (ng < gScore.getOrDefault(neighbour, Double.MAX_VALUE)) {
                    gScore.put(neighbour, ng);
                    cameFrom.put(neighbour, current);
                    open.offer(Map.entry(neighbour, ng + heuristic(neighbour, end)));
                }
            }
        }
        return Collections.emptyList();
    }

    private List<Location> reconstructPath(Map<Node, Node> cameFrom, Node current, org.bukkit.World world) {
        LinkedList<Location> path = new LinkedList<>();
        while (current != null) {
            path.addFirst(new Location(world, current.x + 0.5, current.y, current.z + 0.5));
            current = cameFrom.get(current);
        }
        if (!path.isEmpty()) path.removeFirst(); // skip start node
        return new ArrayList<>(path);
    }

    private static double heuristic(Node a, Node b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
    }

    private static boolean isPassable(Block b) {
        return b.getType().isAir() || !b.getType().isSolid()
                || b.isPassable();
    }

    private static boolean isSolid(Block b) {
        return b.getType().isSolid() && !b.isPassable();
    }

    // =========================================================================
    // Break speed
    // =========================================================================

    /** Approximate number of ticks to break a block (ignores haste/fatigue). */
    private int breakTicks(Material material) {
        // Hardness values approximated from Minecraft wiki; capped for common materials
        double hardness = material.getHardness();
        if (hardness < 0) return MAX_BREAK_TICKS; // unbreakable
        ItemStack tool = bestToolFor(material);
        double speed = toolSpeed(tool, material);
        // ticks = ceil(hardness * 1.5 / speed * 20)  — simplified
        double ticks = Math.ceil(hardness * 1.5 / speed * 20.0);
        return (int) Math.max(1, Math.min(ticks, MAX_BREAK_TICKS));
    }

    private double toolSpeed(ItemStack tool, Material block) {
        if (tool == null) return 1.0;
        Material type = tool.getType();
        // Simplified tool speed table
        if (isPickaxe(type) && isMineable(block, "pickaxe")) return toolTier(type);
        if (isAxe(type)     && isMineable(block, "axe"))     return toolTier(type);
        if (isShovel(type)  && isMineable(block, "shovel"))  return toolTier(type);
        if (isSword(type))                                    return 1.5;
        return 1.0;
    }

    private double toolTier(Material tool) {
        String name = tool.name();
        if (name.startsWith("NETHERITE")) return 9.0;
        if (name.startsWith("DIAMOND"))   return 8.0;
        if (name.startsWith("IRON"))      return 6.0;
        if (name.startsWith("GOLD"))      return 12.0;
        if (name.startsWith("STONE"))     return 4.0;
        if (name.startsWith("WOOD"))      return 2.0;
        return 1.0;
    }

    private boolean isSuitableTool(Material tool, Material block) {
        return (isPickaxe(tool) && isMineable(block, "pickaxe"))
                || (isAxe(tool)    && isMineable(block, "axe"))
                || (isShovel(tool) && isMineable(block, "shovel"));
    }

    private boolean isPickaxe(Material m) { return m.name().endsWith("_PICKAXE"); }
    private boolean isAxe(Material m)     { return m.name().endsWith("_AXE"); }
    private boolean isShovel(Material m)  { return m.name().endsWith("_SHOVEL"); }
    private boolean isSword(Material m)   { return m.name().endsWith("_SWORD"); }

    private boolean isMineable(Material block, String tool) {
        String name = block.name();
        switch (tool) {
            case "pickaxe": return name.contains("STONE") || name.contains("ORE")
                    || name.contains("COBBLE") || name.contains("BRICK")
                    || name.contains("METAL") || name.endsWith("_BLOCK")
                    || block == Material.OBSIDIAN || block == Material.NETHERRACK;
            case "axe": return name.contains("LOG") || name.contains("WOOD")
                    || name.contains("PLANK") || name.contains("FENCE")
                    || name.contains("DOOR") || name.contains("CHEST");
            case "shovel": return block == Material.DIRT || block == Material.GRASS_BLOCK
                    || block == Material.SAND || block == Material.GRAVEL
                    || block == Material.SNOW_BLOCK || block == Material.CLAY;
            default: return false;
        }
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    private static float wrapDeg(float d) {
        d %= 360f;
        if (d > 180f)  d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
