package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class AdminGUICommand implements CommandExecutor {
    private final KokoPixel plugin;

    public AdminGUICommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("kokopixel.admin")) {
            player.sendMessage("§cYou don't have permission to use this.");
            return true;
        }
        plugin.getAdminGUI().openMain(player);
        return true;
    }
}
