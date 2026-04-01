package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.api.game.GameTeam;
import cc.kokodev.kokopixel.party.Party;
import cc.kokodev.kokopixel.ranks.Rank;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Optional;

public class ChatListener implements Listener {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public ChatListener(KokoPixel plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Rank rank = plugin.getRankManager().getPlayerRank(player);
        Optional<GameInstance> game = plugin.getMinigameManager().getGame(player).map(g -> (GameInstance) g);

        event.setCancelled(true);
        Component msg;

        if (game.isPresent()) {
            GameInstance g = game.get();
            Optional<GameTeam> team = g.getPlayerTeam(player.getUniqueId());
            Component prefix = Component.text(ChatColor.translateAlternateColorCodes('&', rank.getFormattedPrefix()));
            prefix = prefix.append(Component.text(" [" + g.getGameType() + "]", NamedTextColor.GOLD));
            if (team.isPresent()) prefix = prefix.append(Component.text("[" + team.get().getName() + "]", NamedTextColor.GRAY));
            msg = prefix.append(Component.text(" " + player.getName() + ": ", NamedTextColor.YELLOW)).append(event.message());
            for (var p : g.getPlayers()) p.getPlayer().sendMessage(msg);
        } else {
            Optional<Party> party = plugin.getPartyManager().getParty(player);
            if (party.isPresent() && event.message().toString().startsWith("@")) {
                msg = Component.text(ChatColor.translateAlternateColorCodes('&', rank.getFormattedPrefix()));
                msg = msg.append(Component.text(" [Party] ", NamedTextColor.AQUA)).append(Component.text(player.getName() + ": ", NamedTextColor.YELLOW)).append(event.message());
                party.get().broadcast(msg);
                return;
            }
            if (!player.getWorld().getName().equals(plugin.getConfig().getString("lobby.world", "world"))) return;
            msg = Component.text(ChatColor.translateAlternateColorCodes('&', rank.getFormattedPrefix()));
            msg = msg.append(Component.text(" [Lobby] ", NamedTextColor.GREEN)).append(Component.text(player.getName() + ": ", NamedTextColor.YELLOW)).append(event.message());
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getWorld().getName().equals(plugin.getConfig().getString("lobby.world", "world"))) online.sendMessage(msg);
            }
        }
    }
}