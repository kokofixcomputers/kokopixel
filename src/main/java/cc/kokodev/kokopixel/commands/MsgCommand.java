package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MsgCommand implements CommandExecutor, TabCompleter {

    private final KokoPixel plugin;
    /** Last person each player messaged — for /r support */
    private final Map<UUID, UUID> lastMsg = new ConcurrentHashMap<>();

    public MsgCommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) { Msg.sendError(sender, "Players only."); return true; }

        // /r — reply to last message
        if (label.equalsIgnoreCase("r")) {
            UUID lastId = lastMsg.get(player.getUniqueId());
            if (lastId == null) { Msg.sendError(player, "You have no one to reply to."); return true; }
            if (args.length == 0) { Msg.sendError(player, "Usage: /r <message>"); return true; }
            String message = String.join(" ", args);
            deliverMessage(player, lastId, message);
            return true;
        }

        if (args.length < 2) { Msg.sendError(player, "Usage: /msg <player> <message>"); return true; }
        String targetName = args[0];
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        // Try local first
        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            deliverMessage(player, localTarget.getUniqueId(), message);
            return true;
        }

        // Try cross-server via BungeeListener network map
        if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null) {
            UUID remoteId = plugin.getBungeeListener().getNetworkPlayerByName(targetName);
            if (remoteId != null) {
                deliverCrossServer(player, remoteId, targetName, message);
                return true;
            }
        }

        Msg.sendError(player, "This player isn't online.");
        return true;
    }

    private void deliverMessage(Player sender, UUID targetId, String message) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            Msg.sendError(sender, "This player isn't online.");
            return;
        }
        sender.sendMessage(Msg.dmSent(target.getName(), message));
        target.sendMessage(Msg.dmReceived(sender.getName(), message));
        lastMsg.put(sender.getUniqueId(), targetId);
        lastMsg.put(targetId, sender.getUniqueId());
    }

    private void deliverCrossServer(Player sender, UUID targetId, String targetName, String message) {
        if (plugin.getBungeeListener() == null) return;
        plugin.getBungeeListener().sendCrossServerMsg(
                sender.getUniqueId(), sender.getName(),
                targetId, targetName, message);
        sender.sendMessage(Msg.dmSent(targetName, message));
        lastMsg.put(sender.getUniqueId(), targetId);
    }

    /** Called when this server receives a cross-server DM for a local player. */
    public void deliverIncoming(UUID fromId, String fromName, UUID toId, String message) {
        Player target = Bukkit.getPlayer(toId);
        if (target == null || !target.isOnline()) return;
        target.sendMessage(Msg.dmReceived(fromName, message));
        lastMsg.put(toId, fromId);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
            // Also include cross-server players if available
            if (plugin.isBungeeEnabled() && plugin.getBungeeListener() != null) {
                return plugin.getBungeeListener().getNetworkPlayerNames().stream()
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .toList();
            }
            return names;
        }
        return List.of();
    }
}
