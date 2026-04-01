package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.ranks.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public AdminCommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("kokopixel.admin")) {
            sender.sendMessage(legacy.deserialize("§cYou don't have permission!"));
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        // Commands that work from console
        switch (args[0].toLowerCase()) {
            case "setrank" -> { setRank(sender, args); return true; }
            case "listranks" -> { listRanks(sender); return true; }
            case "servers" -> { listServers(sender); return true; }
            case "debug" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(legacy.deserialize("§cPlayers only.")); return true; }
                debug(sender, player); return true;
            }
        }

        // Commands that require a player sender
        if (!(sender instanceof Player player)) {
            sender.sendMessage(legacy.deserialize("§cThis command must be used by a player!"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setlobby": plugin.setLobbySpawn(player.getLocation()); player.sendMessage(legacy.deserialize("§aLobby spawn set!")); break;
            case "setworld": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /kokopixel setworld <minigame>")); else setTemplateWorld(player, args[1]); break;
            case "addspawn": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /kokopixel addspawn <minigame> [team]")); else addSpawn(player, args[1], args.length >= 3 ? args[2] : null); break;
            case "clearspawns": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /kokopixel clearspawns <minigame> [team]")); else clearSpawns(player, args[1], args.length >= 3 ? args[2] : null); break;
            case "addteam": if (args.length < 3) player.sendMessage(legacy.deserialize("§cUsage: /kokopixel addteam <minigame> <teamname>")); else addTeam(player, args[1], args[2]); break;
            case "setselector": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /kokopixel setselector <game>")); else setSelectorBlock(player, args[1]); break;
            case "removeselector": removeSelectorBlock(player); break;
            default: sendHelp(sender); break;
        }
        return true;
    }

    private void setRank(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(legacy.deserialize("§cUsage: /kokopixel setrank <player> <rank>"));
            return;
        }
        String targetName = args[1];
        String rankName   = args[2].toLowerCase();

        Rank rank = plugin.getRankManager().getRank(rankName);
        if (rank == null) {
            sender.sendMessage(legacy.deserialize("§cRank '§e" + rankName + "§c' not found. Use /kokopixel listranks to see available ranks."));
            return;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            plugin.getRankManager().setPlayerRank(target, rankName);
            // Refresh tab list name and display name immediately
            plugin.getTabListener().updatePlayerRank(target);
            sender.sendMessage(legacy.deserialize("§aSet §e" + target.getName() + "§a's rank to §e" + rank.getDisplayName() + "§a."));
            target.sendMessage(legacy.deserialize("§aYour rank has been set to §e" + rank.getDisplayName() + "§a."));
        } else {
            // Offline — save to their data file directly
            java.io.File dataFile = new java.io.File(
                    plugin.getDataFolder(), "playerdata/" + targetName + ".yml");
            // Try to find by UUID from existing file
            java.io.File playerDataDir = new java.io.File(plugin.getDataFolder(), "playerdata");
            boolean found = false;
            if (playerDataDir.exists()) {
                for (java.io.File f : playerDataDir.listFiles((d, n) -> n.endsWith(".yml"))) {
                    org.bukkit.configuration.file.YamlConfiguration cfg =
                            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                    // We don't store the name in playerdata, so match by checking if the
                    // offline player UUID resolves to this name
                    try {
                        java.util.UUID uuid = java.util.UUID.fromString(f.getName().replace(".yml", ""));
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        if (targetName.equalsIgnoreCase(op.getName())) {
                            cfg.set("rank", rankName);
                            cfg.save(f);
                            found = true;
                            sender.sendMessage(legacy.deserialize("§aSet §e" + targetName + "§a's rank to §e" + rank.getDisplayName() + "§a (offline)."));
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (!found) {
                sender.sendMessage(legacy.deserialize("§cPlayer '§e" + targetName + "§c' not found. They must have joined the server at least once."));
            }
        }
    }

    private void listRanks(CommandSender sender) {
        sender.sendMessage(legacy.deserialize("§6=== Available Ranks ==="));
        plugin.getRankManager().getRanks().values().stream()
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .forEach(r -> sender.sendMessage(legacy.deserialize(
                        "§e" + r.getName() + " §7(priority: " + r.getPriority() + ") §r"
                        + ChatColor.translateAlternateColorCodes('&', r.getFormattedPrefix())
                        + " §7- " + r.getDisplayName())));
    }

    private void setTemplateWorld(Player player, String name) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } plugin.getMinigameManager().setTemplateWorld(name, player.getWorld()); player.sendMessage(legacy.deserialize("§aSet template world for " + name)); }
    private void addSpawn(Player player, String name, String team) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } if (team != null) plugin.getMinigameManager().addTeamSpawnPoint(name, team, player.getLocation()); else plugin.getMinigameManager().addSpawnPoint(name, player.getLocation()); player.sendMessage(legacy.deserialize("§aAdded spawn point to " + name)); }
    private void clearSpawns(Player player, String name, String team) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } if (team != null) mg.clearTeamSpawnPoints(team); else mg.clearSpawnPoints(); player.sendMessage(legacy.deserialize("§aCleared spawn points")); }
    private void addTeam(Player player, String name, String team) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } mg.setSupportsTeams(true); mg.addTeam(team); player.sendMessage(legacy.deserialize("§aAdded team " + team)); }
    private void setSelectorBlock(Player player, String game) { plugin.setGameSelectorBlock(player.getTargetBlock(null, 5).getLocation(), game); player.sendMessage(legacy.deserialize("§aGame selector block set for " + game)); }
    private void removeSelectorBlock(Player player) { plugin.removeGameSelectorBlock(player.getTargetBlock(null, 5).getLocation()); player.sendMessage(legacy.deserialize("§aGame selector block removed")); }
    private void listServers(CommandSender sender) { sender.sendMessage(legacy.deserialize("§6=== Connected Servers ===")); sender.sendMessage(legacy.deserialize("§7Local server: " + plugin.getServerId())); }
    private void debug(CommandSender sender, Player player) { sender.sendMessage(legacy.deserialize("§6=== Debug Info ===")); sender.sendMessage(legacy.deserialize("§7In game: " + plugin.isInGame(player))); sender.sendMessage(legacy.deserialize("§7In queue: " + plugin.isInQueue(player))); sender.sendMessage(legacy.deserialize("§7Spectating: " + plugin.isSpectating(player))); }
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(legacy.deserialize("§6=== KokoPixel Admin Commands ==="));
        sender.sendMessage(legacy.deserialize("§e/kokopixel setlobby §7- Set lobby spawn"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel setworld <game> §7- Set template world"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel addspawn <game> [team] §7- Add spawn point"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel setselector <game> §7- Set game selector block"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel removeselector §7- Remove game selector block"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel setrank <player> <rank> §7- Set a player's rank"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel listranks §7- List all available ranks"));
        sender.sendMessage(legacy.deserialize("§e/kokopixel servers §7- List connected servers"));
    }

    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) completions.addAll(Arrays.asList(
                "setlobby", "setworld", "addspawn", "clearspawns", "addteam",
                "setselector", "removeselector", "setrank", "listranks", "servers", "debug"));
        else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "setworld", "addspawn", "clearspawns", "addteam", "setselector" ->
                        completions.addAll(plugin.getMinigameManager().getMinigameNames());
                case "setrank" ->
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(completions::add);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setrank")) {
            completions.addAll(plugin.getRankManager().getRanks().keySet());
        }
        return completions.stream().filter(c -> c.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).toList();
    }
}