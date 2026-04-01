package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.Minigame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class MinigameCommand implements CommandExecutor, TabCompleter {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public MinigameCommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { plugin.getGameSelectorMenu().open(player); return true; }

        switch (args[0].toLowerCase()) {
            case "join":
                if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /minigame join <game>"));
                else joinQueue(player, args[1]);
                break;
            case "leave":
                if (plugin.getQueueManager().removeFromQueue(player)) player.sendMessage(legacy.deserialize("§aLeft queue!"));
                else if (plugin.getMinigameManager().isInGame(player)) { plugin.getMinigameManager().removePlayer(player); player.sendMessage(legacy.deserialize("§aLeft game!")); }
                else player.sendMessage(legacy.deserialize("§cYou're not in a queue or game!"));
                break;
            case "list": listMinigames(player); break;
            case "info": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /minigame info <game>")); else showInfo(player, args[1]); break;
            default: plugin.getGameSelectorMenu().open(player); break;
        }
        return true;
    }

    private void joinQueue(Player player, String gameName) {
        Minigame mg = plugin.getMinigameManager().getMinigame(gameName);
        if (mg == null) player.sendMessage(legacy.deserialize("§cMinigame not found: " + gameName));
        else plugin.getQueueManager().addToQueue(player, mg);
    }

    private void listMinigames(Player player) {
        player.sendMessage(legacy.deserialize("§6=== Available Minigames ==="));
        for (Minigame mg : plugin.getMinigameManager().getMinigames()) {
            int q = plugin.getQueueManager().getQueueSize(mg.getName());
            player.sendMessage(legacy.deserialize("§e" + mg.getDisplayName() + " §7[" + mg.getMinPlayers() + "-" + mg.getMaxPlayers() + "] - Queue: " + (q > 0 ? "§a" + q : "§c" + q)));
        }
    }

    private void showInfo(Player player, String gameName) {
        Minigame mg = plugin.getMinigameManager().getMinigame(gameName);
        if (mg == null) { player.sendMessage(legacy.deserialize("§cMinigame not found")); return; }
        player.sendMessage(legacy.deserialize("§6=== " + mg.getDisplayName() + " ==="));
        player.sendMessage(legacy.deserialize("§7Players: §f" + mg.getMinPlayers() + "-" + mg.getMaxPlayers()));
        player.sendMessage(legacy.deserialize("§7Teams: §f" + (mg.supportsTeams() ? "Yes" : "No")));
        player.sendMessage(legacy.deserialize("§7In Queue: §f" + plugin.getQueueManager().getQueueSize(mg.getName())));
    }

    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) completions.addAll(List.of("join", "leave", "list", "info"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("info"))) completions.addAll(plugin.getMinigameManager().getMinigameNames());
        return completions.stream().filter(c -> c.toLowerCase().startsWith(args[args.length - 1].toLowerCase())).toList();
    }
}