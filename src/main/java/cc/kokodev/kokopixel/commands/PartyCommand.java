package cc.kokodev.kokopixel.commands;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.party.Party;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class PartyCommand implements CommandExecutor, TabCompleter {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public PartyCommand(KokoPixel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "create": createParty(player); break;
            case "invite": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /party invite <player>")); else invitePlayer(player, args[1]); break;
            case "accept": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /party accept <player>")); else acceptInvite(player, args[1]); break;
            case "leave": leaveParty(player); break;
            case "kick": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /party kick <player>")); else kickPlayer(player, args[1]); break;
            case "disband": disbandParty(player); break;
            case "private": togglePrivate(player, true); break;
            case "public": togglePrivate(player, false); break;
            case "list": listParty(player); break;
            case "transfer": if (args.length < 2) player.sendMessage(legacy.deserialize("§cUsage: /party transfer <player>")); else transferLeader(player, args[1]); break;
            default: sendHelp(player); break;
        }
        return true;
    }

    private void createParty(Player player) {
        if (plugin.getPartyManager().isInParty(player)) { player.sendMessage(legacy.deserialize("§cYou're already in a party!")); return; }
        plugin.getPartyManager().createParty(player);
        player.sendMessage(legacy.deserialize("§aParty created! Use §e/party invite <player>§a to add members."));
    }

    private void invitePlayer(Player player, String targetName) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) { player.sendMessage(legacy.deserialize("§cPlayer not found!")); return; }
        party.get().invite(player, target);
    }

    private void acceptInvite(Player player, String inviterName) {
        Player inviter = Bukkit.getPlayer(inviterName);
        if (inviter == null) { player.sendMessage(legacy.deserialize("§cPlayer not found!")); return; }
        Optional<Party> party = plugin.getPartyManager().getParty(inviter);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cThat party no longer exists!")); return; }
        party.get().accept(player, inviter);
    }

    private void leaveParty(Player player) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        party.get().leave(player);
    }

    private void kickPlayer(Player player, String targetName) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) { player.sendMessage(legacy.deserialize("§cPlayer not found!")); return; }
        party.get().kick(player, target);
    }

    private void disbandParty(Player player) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        if (!party.get().isLeader(player)) { player.sendMessage(legacy.deserialize("§cOnly the party leader can disband!")); return; }
        party.get().disband();
        plugin.getPartyManager().removeParty(party.get().getPartyId());
    }

    private void togglePrivate(Player player, boolean isPrivate) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        if (!party.get().isLeader(player)) { player.sendMessage(legacy.deserialize("§cOnly the party leader can change privacy!")); return; }
        party.get().setPrivate(isPrivate);
    }

    private void listParty(Player player) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        player.sendMessage(legacy.deserialize("§6=== Party Members ==="));
        player.sendMessage(legacy.deserialize("§eLeader: §f" + party.get().getLeader().getName()));
        for (Player member : party.get().getMembers()) {
            if (!member.equals(party.get().getLeader())) {
                player.sendMessage(legacy.deserialize("§7  • §f" + member.getName()));
            }
        }
        player.sendMessage(legacy.deserialize("§7Privacy: " + (party.get().isPrivate() ? "§cPrivate" : "§aPublic")));
    }

    private void transferLeader(Player player, String targetName) {
        Optional<Party> party = plugin.getPartyManager().getParty(player);
        if (party.isEmpty()) { player.sendMessage(legacy.deserialize("§cYou're not in a party!")); return; }
        if (!party.get().isLeader(player)) { player.sendMessage(legacy.deserialize("§cOnly the party leader can transfer leadership!")); return; }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) { player.sendMessage(legacy.deserialize("§cPlayer not found!")); return; }
        party.get().transfer(target);
    }

    private void sendHelp(Player player) {
        player.sendMessage(legacy.deserialize("§6=== Party Commands ==="));
        player.sendMessage(legacy.deserialize("§e/party create §7- Create a party"));
        player.sendMessage(legacy.deserialize("§e/party invite <player> §7- Invite a player"));
        player.sendMessage(legacy.deserialize("§e/party accept <player> §7- Accept an invite"));
        player.sendMessage(legacy.deserialize("§e/party leave §7- Leave your party"));
        player.sendMessage(legacy.deserialize("§e/party kick <player> §7- Kick a member"));
        player.sendMessage(legacy.deserialize("§e/party disband §7- Disband your party"));
        player.sendMessage(legacy.deserialize("§e/party private/public §7- Change party privacy"));
        player.sendMessage(legacy.deserialize("§e/party list §7- List party members"));
        player.sendMessage(legacy.deserialize("§e/party transfer <player> §7- Transfer leadership"));
    }

    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "invite", "accept", "leave", "kick", "disband", "private", "public", "list", "transfer");
        if (args.length == 2 && (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("kick") || args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("transfer"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return new ArrayList<>();
    }
}