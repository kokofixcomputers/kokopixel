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
            case "servers": listServers(sender); break;
            case "debug": debug(sender, player); break;
            default: sendHelp(sender); break;
        }
        return true;
    }

    private void setTemplateWorld(Player player, String name) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } plugin.getMinigameManager().setTemplateWorld(name, player.getWorld()); player.sendMessage(legacy.deserialize("§aSet template world for " + name)); }
    private void addSpawn(Player player, String name, String team) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } if (team != null) plugin.getMinigameManager().addTeamSpawnPoint(name, team, player.getLocation()); else plugin.getMinigameManager().addSpawnPoint(name, player.getLocation()); player.sendMessage(legacy.deserialize("§aAdded spawn point to " + name)); }
    private void clearSpawns(Player player, String name, String team) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } if (team != null) mg.clearTeamSpawnPoints(team); else mg.clearSpawnPoints(); player.sendMessage(legacy.deserialize("§aCleared spawn points")); }
    private void addTeam(Player player, String name, String team) { var mg = plugin.getMinigameManager().getMinigame(name); if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; } mg.setSupportsTeams(true); mg.addTeam(team); player.sendMessage(legacy.deserialize("§aAdded team " + team)); }
    private void setSelectorBlock(Player player, String game) { plugin.setGameSelectorBlock(player.getTargetBlock(null, 5).getLocation(), game); player.sendMessage(legacy.deserialize("§aGame selector block set for " + game)); }
    private void removeSelectorBlock(Player player) { plugin.removeGameSelectorBlock(player.getTargetBlock(null, 5).getLocation()); player.sendMessage(legacy.deserialize("§aGame selector block removed")); }
    private void listServers(CommandSender sender) { sender.sendMessage(legacy.deserialize("§6=== Connected Servers ===")); sender.sendMessage(legacy.deserialize("§7Local server: " + plugin.getServerId())); }
    private void debug(CommandSender sender, Player player) { sender.sendMessage(legacy.deserialize("§6=== Debug Info ===")); sender.sendMessage(legacy.deserialize("§7In game: " + plugin.isInGame(player))); sender.sendMessage(legacy.deserialize("§7In queue: " + plugin.isInQueue(player))); sender.sendMessage(legacy.deserialize("§7Spectating: " + plugin.isSpectating(player))); }
    private void sendHelp(CommandSender sender) { sender.sendMessage(legacy.deserialize("§6=== KokoPixel Admin Commands ===")); sender.sendMessage(legacy.deserialize("§e/kokopixel setlobby §7- Set lobby spawn")); sender.sendMessage(legacy.deserialize("§e/kokopixel setworld <game> §7- Set template world")); sender.sendMessage(legacy.deserialize("§e/kokopixel addspawn <game> [team] §7- Add spawn point")); sender.sendMessage(legacy.deserialize("§e/kokopixel setselector <game> §7- Set game selector block")); sender.sendMessage(legacy.deserialize("§e/kokopixel removeselector §7- Remove game selector block")); sender.sendMessage(legacy.deserialize("§e/kokopixel servers §7- List connected servers")); }

    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) completions.addAll(Arrays.asList("setlobby", "setworld", "addspawn", "clearspawns", "addteam", "setselector", "removeselector", "servers", "debug"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("setworld") || args[0].equalsIgnoreCase("addspawn") || args[0].equalsIgnoreCase("clearspawns") || args[0].equalsIgnoreCase("addteam") || args[0].equalsIgnoreCase("setselector"))) completions.addAll(plugin.getMinigameManager().getMinigameNames());
        return completions.stream().filter(c -> c.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).toList();
    }
}