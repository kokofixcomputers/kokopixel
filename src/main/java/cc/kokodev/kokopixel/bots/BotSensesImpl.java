package cc.kokodev.kokopixel.bots;

import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.bot.BotSenses;
import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameTeam;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

public class BotSensesImpl implements BotSenses {

    private static final double EYE_HEIGHT = 1.62;

    private final BotHandle bot;
    // UUID of the bot's own team, cached per-game query
    private UUID cachedTeamGameId = null;
    private GameTeam cachedTeam = null;

    public BotSensesImpl(BotHandle bot) {
        this.bot = bot;
    }

    // -------------------------------------------------------------------------
    // Vision
    // -------------------------------------------------------------------------

    @Override
    public boolean canSeeLocation(Location target, double maxDistance, float fovHalfDegrees) {
        Location eye = eyeLocation();
        if (!Objects.equals(eye.getWorld(), target.getWorld())) return false;
        double dist = eye.distance(target);
        if (dist > maxDistance) return false;

        // Vector from eye to target
        Vector toTarget = target.toVector().subtract(eye.toVector()).normalize();
        // Bot's facing direction from yaw/pitch
        Vector facing = eye.getDirection();

        double dot = facing.dot(toTarget);
        double cosHalf = Math.cos(Math.toRadians(fovHalfDegrees));
        return dot >= cosHalf;
    }

    @Override
    public boolean hasLineOfSight(Block block) {
        Location eye = eyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        Vector dir = blockCenter.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.001) return true;
        RayTraceResult result = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), dist,
                org.bukkit.FluidCollisionMode.NEVER, true);
        return result == null || Objects.equals(result.getHitBlock(), block);
    }

    @Override
    public boolean hasLineOfSight(Player player) {
        Location eye = eyeLocation();
        Location playerEye = player.getEyeLocation();
        Vector dir = playerEye.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.001) return true;
        RayTraceResult result = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), dist,
                org.bukkit.FluidCollisionMode.NEVER, true);
        return result == null;
    }

    // -------------------------------------------------------------------------
    // Block scanning
    // -------------------------------------------------------------------------

    @Override
    public List<Block> getNearbyBlocks(double radius, Material... filter) {
        Location center = bot.getLocation();
        World world = center.getWorld();
        Set<Material> filterSet = filter.length > 0 ? new HashSet<>(Arrays.asList(filter)) : null;
        List<Block> result = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = world.getBlockAt(
                            center.getBlockX() + x,
                            center.getBlockY() + y,
                            center.getBlockZ() + z);
                    if (b.getType() == Material.AIR) continue;
                    if (filterSet != null && !filterSet.contains(b.getType())) continue;
                    if (b.getLocation().add(0.5, 0.5, 0.5).distance(center) <= radius) {
                        result.add(b);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public Optional<Block> findNearestBlock(double radius, Material... materials) {
        List<Block> found = getNearbyBlocks(radius, materials);
        Location center = bot.getLocation();
        return found.stream()
                .min(Comparator.comparingDouble(b -> b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center)));
    }

    @Override
    public Optional<Block> getTargetBlock(double maxDistance) {
        Location eye = eyeLocation();
        RayTraceResult result = eye.getWorld().rayTraceBlocks(eye, eye.getDirection(), maxDistance,
                org.bukkit.FluidCollisionMode.NEVER, false);
        return result == null ? Optional.empty() : Optional.ofNullable(result.getHitBlock());
    }

    @Override
    public Block getBlockBelow() {
        Location loc = bot.getLocation();
        return loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
    }

    @Override
    public boolean isOnGround() {
        Block below = getBlockBelow();
        // For bots the shadow stand has no gravity so it floats at placed Y;
        // consider the bot "on ground" if there's a solid block within 1.5 blocks below.
        if (below.getType().isAir() || !below.getType().isSolid()) {
            // Check one more block down
            Location loc = bot.getLocation();
            Block twoBelow = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 2, loc.getBlockZ());
            return twoBelow.getType().isSolid();
        }
        return true;
    }

    @Override
    public boolean isAtEdge() {
        Location loc = bot.getLocation();
        Vector forward = loc.getDirection().setY(0).normalize();
        Location oneForward = loc.clone().add(forward);
        Block groundAhead = loc.getWorld().getBlockAt(
                oneForward.getBlockX(), loc.getBlockY() - 1, oneForward.getBlockZ());
        return groundAhead.getType().isAir();
    }

    @Override
    public Block getBlockAtHead() {
        Location loc = bot.getLocation();
        return loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
    }

    // -------------------------------------------------------------------------
    // Player perception
    // -------------------------------------------------------------------------

    @Override
    public List<Player> getNearbyPlayers(double radius) {
        return bot.getLocation().getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distance(bot.getLocation()) <= radius)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getNearestPlayer(double radius) {
        Location loc = bot.getLocation();
        return getNearbyPlayers(radius).stream()
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(loc)));
    }

    @Override
    public Optional<GamePlayer> getNearestEnemy(double radius, GameInstance game) {
        Optional<GameTeam> ownTeam = getOwnTeam(game);
        Location loc = bot.getLocation();
        return game.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .filter(gp -> ownTeam.map(t -> !t.isMember(gp.getUniqueId())).orElse(true))
                .filter(gp -> gp.getPlayer().getLocation().distance(loc) <= radius)
                .min(Comparator.comparingDouble(gp -> gp.getPlayer().getLocation().distanceSquared(loc)));
    }

    @Override
    public Optional<GamePlayer> getNearestAlly(double radius, GameInstance game) {
        Optional<GameTeam> ownTeam = getOwnTeam(game);
        if (ownTeam.isEmpty()) return Optional.empty();
        GameTeam team = ownTeam.get();
        Location loc = bot.getLocation();
        return game.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .filter(gp -> team.isMember(gp.getUniqueId()))
                .filter(gp -> gp.getPlayer().getLocation().distance(loc) <= radius)
                .min(Comparator.comparingDouble(gp -> gp.getPlayer().getLocation().distanceSquared(loc)));
    }

    @Override
    public List<GamePlayer> getVisibleEnemies(GameInstance game) {
        Optional<GameTeam> ownTeam = getOwnTeam(game);
        return game.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .filter(gp -> ownTeam.map(t -> !t.isMember(gp.getUniqueId())).orElse(true))
                .filter(gp -> hasLineOfSight(gp.getPlayer()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Team awareness
    // -------------------------------------------------------------------------

    @Override
    public List<GameTeam> getAliveTeams(GameInstance game) {
        return game.getTeams().stream()
                .filter(t -> !t.isEliminated())
                .collect(Collectors.toList());
    }

    @Override
    public Optional<GameTeam> getOwnTeam(GameInstance game) {
        // Cache per game instance to avoid O(n) scan every tick
        if (Objects.equals(cachedTeamGameId, game.getGameId()) && cachedTeam != null) {
            return Optional.of(cachedTeam);
        }
        // Bot UUID is stored in bot's handle; look up by UUID in each team
        for (GameTeam team : game.getTeams()) {
            if (team.isMember(bot.getUniqueId())) {
                cachedTeamGameId = game.getGameId();
                cachedTeam = team;
                return Optional.of(team);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isAlly(Player player, GameInstance game) {
        return getOwnTeam(game)
                .map(t -> t.isMember(player.getUniqueId()))
                .orElse(false);
    }

    @Override
    public boolean isEnemy(GamePlayer player, GameInstance game) {
        return getOwnTeam(game)
                .map(t -> !t.isMember(player.getUniqueId()))
                .orElse(true);
    }

    // -------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------

    @Override
    public double horizontalDistanceTo(Location location) {
        Location here = bot.getLocation();
        double dx = location.getX() - here.getX();
        double dz = location.getZ() - here.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public double distanceTo(Location location) {
        return bot.getLocation().distance(location);
    }

    @Override
    public float yawToward(Location target) {
        Location from = bot.getLocation();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)) % 360);
    }

    @Override
    public float pitchToward(Location target) {
        Location from = eyeLocation();
        double dx = target.getX() - from.getX();
        double dy = target.getY() - from.getY();
        double dz = target.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horiz));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Location eyeLocation() {
        return bot.getLocation().add(0, EYE_HEIGHT, 0);
    }
}
