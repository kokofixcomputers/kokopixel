package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.friends.FriendManager;
import cc.kokodev.kokopixel.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class FriendCommand implements CommandExecutor, TabCompleter {

    private final KokoPixel plugin;

    public FriendCommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) { Msg.sendError(sender, "Players only."); return true; }
        if (args.length == 0) { sendHelp(player); return true; }

        FriendManager fm = plugin.getFriendManager();
        switch (args[0].toLowerCase()) {
            case "add", "request" -> {
                if (args.length < 2) { Msg.sendError(player, "Usage: /friend add <player>"); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { Msg.sendError(player, "That player isn't online."); return true; }
                fm.sendRequest(player, target);
            }
            case "accept" -> {
                if (args.length < 2) { Msg.sendError(player, "Usage: /friend accept <player>"); return true; }
                Player requester = Bukkit.getPlayer(args[1]);
                if (requester == null) { Msg.sendError(player, "That player isn't online."); return true; }
                fm.acceptRequest(player, requester);
            }
            case "deny" -> {
                if (args.length < 2) { Msg.sendError(player, "Usage: /friend deny <player>"); return true; }
                fm.denyRequest(player, args[1]);
            }
            case "remove", "unfriend" -> {
                if (args.length < 2) { Msg.sendError(player, "Usage: /friend remove <player>"); return true; }
                fm.removeFriend(player, args[1]);
            }
            case "list" -> listFriends(player, fm);
            default -> sendHelp(player);
        }
        return true;
    }

    private void listFriends(Player player, FriendManager fm) {
        Set<UUID> friendIds = fm.getFriends(player.getUniqueId());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Friends (" + friendIds.size() + ")", NamedTextColor.AQUA, TextDecoration.BOLD));

        if (friendIds.isEmpty()) {
            lines.add(Msg.info("You have no friends yet. Use /friend add <player>"));
        } else {
            List<UUID> online = new ArrayList<>(), offline = new ArrayList<>();
            for (UUID fid : friendIds) {
                if (fm.isFriendOnline(fid)) online.add(fid); else offline.add(fid);
            }
            for (UUID fid : online) {
                String server = fm.getFriendServer(fid);
                lines.add(Component.text("  ● ", NamedTextColor.GREEN)
                        .append(Msg.name(fm.getName(fid)))
                        .append(Component.text(" — " + server, NamedTextColor.GRAY))
                        .append(Component.text("  ", NamedTextColor.GRAY))
                        .append(Msg.clickableSuggest("MSG", "/msg " + fm.getName(fid) + " ", "Send a message")));
            }
            for (UUID fid : offline) {
                lines.add(Component.text("  ○ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(fm.getName(fid), NamedTextColor.GRAY)));
            }
        }

        player.sendMessage(Msg.box(lines.toArray(new Component[0])));
    }

    private void sendHelp(Player player) {
        player.sendMessage(Msg.box(
            Component.text("Friend Commands", NamedTextColor.AQUA, TextDecoration.BOLD),
            Msg.label("/friend add <player>",    "Send a friend request"),
            Msg.label("/friend accept <player>", "Accept a request"),
            Msg.label("/friend deny <player>",   "Deny a request"),
            Msg.label("/friend remove <player>", "Remove a friend"),
            Msg.label("/friend list",             "View your friends")
        ));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (args.length == 1)
            return List.of("add", "accept", "deny", "remove", "list").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2)
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        return List.of();
    }
}
