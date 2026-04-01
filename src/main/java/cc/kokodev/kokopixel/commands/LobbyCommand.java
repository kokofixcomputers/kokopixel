package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class LobbyCommand implements CommandExecutor {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public LobbyCommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return true;
        }

        if (plugin.getQueueManager().removeFromQueue(player)) {
            player.sendMessage(legacy.deserialize("§aLeft queue and returned to lobby!"));
        } else if (plugin.getMinigameManager().isInGame(player)) {
            plugin.getMinigameManager().removePlayer(player);
            player.sendMessage(legacy.deserialize("§aLeft game and returned to lobby!"));
        } else if (plugin.isSpectating(player)) {
            plugin.disableSpectator(player);
            player.teleport(plugin.getLobbySpawn());
            plugin.getGameSelectorMenu().giveGameSelector(player);
            player.sendMessage(legacy.deserialize("§aLeft spectator mode!"));
        } else {
            player.teleport(plugin.getLobbySpawn());
            player.sendMessage(legacy.deserialize("§aWelcome to the lobby!"));
        }
        return true;
    }
}