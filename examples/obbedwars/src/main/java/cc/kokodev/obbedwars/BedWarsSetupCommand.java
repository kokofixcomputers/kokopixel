package cc.kokodev.obbedwars;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * /obbedwars setup bed <team>   — register the bed you are looking at for a team
 * /obbedwars setup diamondgen   — register a diamond generator at your feet
 * /obbedwars setup spawn <team> — register a spawn point for a team
 * /obbedwars setup save         — save the map config
 * /obbedwars setup info         — show current setup
 */
public class BedWarsSetupCommand implements CommandExecutor, TabCompleter {

    private final OBBedWars plugin;

    public BedWarsSetupCommand(OBBedWars plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (!player.hasPermission("obbedwars.setup")) {
            player.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("setup")) {
            sendHelp(player); return true;
        }

        BedWarsMinigame mg = plugin.getMinigame();
        if (mg == null) { player.sendMessage("§cMinigame not loaded yet."); return true; }

        switch (args[1].toLowerCase()) {
            case "bed" -> {
                if (args.length < 3) { player.sendMessage("§cUsage: /obbedwars setup bed <team>"); return true; }
                String team = args[2].toLowerCase();
                Block target = getBedTarget(player);
                if (target == null) { player.sendMessage("§cLook at a bed block."); return true; }
                // Normalise to the foot of the bed
                Location footLoc = getBedFoot(target);
                mg.setBedLocation(team, footLoc);
                player.sendMessage("§aBed for team §e" + team + " §aset at §f"
                        + formatLoc(footLoc) + "§a.");
            }
            case "diamondgen" -> {
                Location loc = player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                mg.addDiamondGen(loc);
                player.sendMessage("§aDiamond generator added at §f" + formatLoc(loc) + "§a.");
            }
            case "spawn" -> {
                if (args.length < 3) { player.sendMessage("§cUsage: /obbedwars setup spawn <team>"); return true; }
                String team = args[2].toLowerCase();
                // Delegate to KokoPixel's admin command logic
                cc.kokodev.kokopixel.KokoPixel.getInstance()
                        .getMinigameManager()
                        .addTeamSpawnPoint("obbedwars", team, player.getLocation());
                player.sendMessage("§aSpawn for team §e" + team + " §aset.");
            }
            case "save" -> {
                mg.saveMapConfig(plugin);
                player.sendMessage("§aMap config saved.");
            }
            case "info" -> {
                player.sendMessage("§6=== OBBedWars Setup ===");
                player.sendMessage("§7Beds configured: §f" + mg.getBedLocations().size());
                mg.getBedLocations().forEach((t, l) ->
                        player.sendMessage("  §e" + t + " §7→ §f" + formatLoc(l)));
                player.sendMessage("§7Diamond gens: §f" + mg.getDiamondGens().size());
            }
            default -> sendHelp(player);
        }
        return true;
    }

    /** Returns the bed block the player is looking at (within 5 blocks), or null. */
    private Block getBedTarget(Player player) {
        Block target = player.getTargetBlockExact(5);
        if (target == null) return null;
        if (target.getBlockData() instanceof Bed) return target;
        // Also check the block the player is standing on top of
        Block below = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (below.getBlockData() instanceof Bed) return below;
        return null;
    }

    /** Returns the foot half of a bed. */
    private Location getBedFoot(Block bedBlock) {
        if (bedBlock.getBlockData() instanceof Bed bed) {
            if (bed.getPart() == Bed.Part.HEAD) {
                // Move to foot
                BlockFace facing = bed.getFacing();
                return bedBlock.getRelative(facing.getOppositeFace()).getLocation();
            }
        }
        return bedBlock.getLocation();
    }

    private String formatLoc(Location l) {
        return String.format("%.0f,%.0f,%.0f", l.getX(), l.getY(), l.getZ());
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== OBBedWars Setup ===");
        player.sendMessage("§e/obbedwars setup bed <team> §7- Register bed you're looking at");
        player.sendMessage("§e/obbedwars setup spawn <team> §7- Register spawn at your position");
        player.sendMessage("§e/obbedwars setup diamondgen §7- Add diamond gen at your feet");
        player.sendMessage("§e/obbedwars setup save §7- Save map config");
        player.sendMessage("§e/obbedwars setup info §7- Show current setup");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (args.length == 1) return List.of("setup");
        if (args.length == 2) return List.of("bed", "spawn", "diamondgen", "save", "info");
        if (args.length == 3 && (args[1].equalsIgnoreCase("bed") || args[1].equalsIgnoreCase("spawn"))) {
            BedWarsMinigame mg = plugin.getMinigame();
            if (mg != null) return mg.getTeams().stream()
                    .filter(t -> t.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
