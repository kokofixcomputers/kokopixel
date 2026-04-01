package cc.kokodev.kokopixel.listeners;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.ranks.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class TabListener implements Listener {
    private final KokoPixel plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
    private Scoreboard scoreboard;

    public TabListener(KokoPixel plugin) {
        this.plugin = plugin;
        this.scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : scoreboard.getTeams()) if (t.getName().startsWith("rank_")) t.unregister();
        createTeams();
    }

    private void createTeams() {
        for (Rank rank : plugin.getRankManager().getRanks().values()) {
            Team team = scoreboard.getTeam("rank_" + rank.getPriority());
            if (team == null) team = scoreboard.registerNewTeam("rank_" + rank.getPriority());
            team.prefix(legacy.deserialize(ChatColor.translateAlternateColorCodes('&', rank.getFormattedPrefix() + " ")));
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
    }

    public void updatePlayerRank(Player player) {
        Rank rank = plugin.getRankManager().getPlayerRank(player);
        Team team = scoreboard.getTeam("rank_" + rank.getPriority());
        if (team == null) { createTeams(); team = scoreboard.getTeam("rank_" + rank.getPriority()); }
        for (Team t : scoreboard.getTeams()) if (t.getName().startsWith("rank_") && !t.equals(team)) t.removeEntry(player.getName());
        team.addEntry(player.getName());
        player.playerListName(legacy.deserialize(ChatColor.translateAlternateColorCodes('&', rank.getFormattedPrefix() + " " + rank.getColor() + player.getName() + "&r")));
        player.setDisplayName(rank.getColor() + player.getName() + ChatColor.RESET);
        player.setCustomName(rank.getColor() + player.getName() + ChatColor.RESET);
        player.setCustomNameVisible(false);
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent e) { updatePlayerRank(e.getPlayer()); Bukkit.getScheduler().runTaskLater(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) updatePlayerRank(p); }, 10L); }
    @EventHandler public void onPlayerQuit(PlayerQuitEvent e) { for (Team t : scoreboard.getTeams()) if (t.getName().startsWith("rank_")) t.removeEntry(e.getPlayer().getName()); }
}