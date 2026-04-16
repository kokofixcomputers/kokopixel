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
            if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
                handleAdmin(player, args); return true;
            }
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
            case "shop" -> {
                Location loc = player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                mg.addShop(loc);
                player.sendMessage("§aShop NPC location added at §f" + formatLoc(loc) + "§a.");
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
                player.sendMessage("§7Shops: §f" + mg.getShopLocations().size());
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
        player.sendMessage("§e/obbedwars setup shop §7- Add shop NPC at your feet");
        player.sendMessage("§e/obbedwars setup save §7- Save map config");
        player.sendMessage("§e/obbedwars setup info §7- Show current setup");
        player.sendMessage("§e/obbedwars admin give <item> §7- Give yourself a custom item");
        player.sendMessage("§e/obbedwars admin gui §7- Open item browser GUI");
    }

    // -------------------------------------------------------------------------
    // Admin subcommand
    // -------------------------------------------------------------------------

    static final String ADMIN_GUI_TITLE = "Admin Item Browser";
    private static final int PAGE_SIZE = 45; // 5 rows of 9, bottom row = nav

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("obbedwars.admin")) {
            player.sendMessage("§cNo permission."); return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("gui")) {
            openAdminGui(player, 0);
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("give")) {
            String key = args[2].toLowerCase();
            org.bukkit.inventory.ItemStack item = BedWarsGame.getLootTableItems().get(key);
            if (item == null) {
                player.sendMessage("§cUnknown item §e" + key + "§c. Use tab to see options.");
                return;
            }
            player.getInventory().addItem(item.clone());
            player.sendMessage("§aGave you §e" + key + "§a.");
        } else {
            player.sendMessage("§eUsage: /obbedwars admin gui  |  /obbedwars admin give <item>");
        }
    }

    void openAdminGui(Player player, int page) {
        java.util.List<org.bukkit.inventory.ItemStack> items =
                new java.util.ArrayList<>(BedWarsGame.getLootTableItems().values());
        int totalPages = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        org.bukkit.inventory.Inventory gui = org.bukkit.Bukkit.createInventory(null, 54,
                net.kyori.adventure.text.Component.text(ADMIN_GUI_TITLE)
                        .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));

        // Fill item slots (0-44)
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx < items.size()) {
                org.bukkit.inventory.ItemStack display = items.get(idx).clone();
                // Tag with index so click handler knows what to give
                org.bukkit.inventory.meta.ItemMeta m = display.getItemMeta();
                if (m != null) {
                    m.getPersistentDataContainer().set(
                            new org.bukkit.NamespacedKey("obbedwars", "admin_gui_idx"),
                            org.bukkit.persistence.PersistentDataType.INTEGER, idx);
                    display.setItemMeta(m);
                }
                gui.setItem(i, display);
            }
        }

        // Navigation row (slots 45-53)
        org.bukkit.inventory.ItemStack filler = new org.bukkit.inventory.ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta fm = filler.getItemMeta();
        fm.displayName(net.kyori.adventure.text.Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = 45; i < 54; i++) gui.setItem(i, filler);

        // Page info
        org.bukkit.inventory.ItemStack pageInfo = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta pm = pageInfo.getItemMeta();
        pm.displayName(net.kyori.adventure.text.Component.text("§ePage " + (page + 1) + " / " + totalPages)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        // Store current page in PDC for click handler
        pm.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "admin_gui_page"),
                org.bukkit.persistence.PersistentDataType.INTEGER, page);
        pm.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("obbedwars", "admin_gui_total"),
                org.bukkit.persistence.PersistentDataType.INTEGER, totalPages);
        pageInfo.setItemMeta(pm);
        gui.setItem(49, pageInfo);

        if (page > 0) {
            org.bukkit.inventory.ItemStack prev = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW);
            org.bukkit.inventory.meta.ItemMeta am = prev.getItemMeta();
            am.displayName(net.kyori.adventure.text.Component.text("§e← Previous Page")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            am.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "admin_gui_nav"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, page - 1);
            prev.setItemMeta(am);
            gui.setItem(45, prev);
        }

        if (page < totalPages - 1) {
            org.bukkit.inventory.ItemStack next = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW);
            org.bukkit.inventory.meta.ItemMeta am = next.getItemMeta();
            am.displayName(net.kyori.adventure.text.Component.text("§eNext Page →")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            am.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("obbedwars", "admin_gui_nav"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, page + 1);
            next.setItemMeta(am);
            gui.setItem(53, next);
        }

        player.openInventory(gui);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (args.length == 1) return filter(List.of("setup", "admin"), args[0]);
        if (args[0].equalsIgnoreCase("setup")) {
            if (args.length == 2) return filter(List.of("bed", "spawn", "diamondgen", "shop", "save", "info"), args[1]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("bed") || args[1].equalsIgnoreCase("spawn"))) {
                BedWarsMinigame mg = plugin.getMinigame();
                if (mg != null) return filter(mg.getTeams().stream().toList(), args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (args.length == 2) return filter(List.of("give", "gui"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("give"))
                return filter(new java.util.ArrayList<>(BedWarsGame.getLootTableItems().keySet()), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }
}
