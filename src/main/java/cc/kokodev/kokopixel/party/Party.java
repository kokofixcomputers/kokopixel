package cc.kokodev.kokopixel.party;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
public class Party {
    /** A bot slot added to the party: engine id + number of bots requested. */
    public record BotSlot(String engineId, int count) {}

    private final UUID partyId;
    private final Player leader;
    private final List<Player> members = new CopyOnWriteArrayList<>();
    private final Map<UUID, Player> pendingInvites = new HashMap<>();
    private boolean isPrivate = false;
    private final KokoPixel plugin;
    private final List<BotSlot> botSlots = new CopyOnWriteArrayList<>();

    public Party(KokoPixel plugin, Player leader) { this.plugin = plugin; this.partyId = UUID.randomUUID(); this.leader = leader; this.members.add(leader); }
    public UUID getPartyId() { return partyId; }
    public Player getLeader() { return leader; }
    public List<Player> getMembers() { return new ArrayList<>(members); }
    public List<Player> getOnlineMembers() { return members.stream().filter(Player::isOnline).toList(); }
    public int getSize() { return members.size(); }
    public boolean isMember(Player p) { return members.contains(p); }
    public boolean isLeader(Player p) { return leader.equals(p); }
    public boolean isPrivate() { return isPrivate; }

    // -------------------------------------------------------------------------
    // Bot slots
    // -------------------------------------------------------------------------

    /** Adds a bot slot (or replaces an existing one for the same engine). */
    public void addBotSlot(String engineId, int count) {
        botSlots.removeIf(s -> s.engineId().equalsIgnoreCase(engineId));
        if (count > 0) botSlots.add(new BotSlot(engineId.toLowerCase(), count));
    }

    /** Removes all bot slots for the given engine. */
    public void removeBotSlot(String engineId) {
        botSlots.removeIf(s -> s.engineId().equalsIgnoreCase(engineId));
    }

    /** Returns all pending bot slots. */
    public List<BotSlot> getBotSlots() { return List.copyOf(botSlots); }

    /** Total number of bot fill slots across all engines. */
    public int getTotalBotCount() { return botSlots.stream().mapToInt(BotSlot::count).sum(); }

    public void invite(Player inviter, Player target) {
        if (!isLeader(inviter)) { inviter.sendMessage(Msg.error("Only the party leader can invite players!")); return; }
        if (pendingInvites.containsKey(target.getUniqueId())) { inviter.sendMessage(Msg.error("That player already has a pending invite!")); return; }
        pendingInvites.put(target.getUniqueId(), inviter);
        target.sendMessage(Msg.partyInvite(leader.getName()));
        inviter.sendMessage(Msg.success("Invited " + target.getName() + " to the party!"));
    }

    public boolean accept(Player player, Player inviter) {
        if (!pendingInvites.containsKey(player.getUniqueId()) || !pendingInvites.get(player.getUniqueId()).equals(inviter)) {
            player.sendMessage(Msg.error("You don't have a pending invite from this player!")); return false;
        }
        pendingInvites.remove(player.getUniqueId()); members.add(player);
        broadcast(Msg.name(player.getName()).append(Component.text(" joined the party!", NamedTextColor.GREEN)));
        return true;
    }

    public void kick(Player kicker, Player target) {
        if (!isLeader(kicker)) { kicker.sendMessage(Msg.error("Only the party leader can kick members!")); return; }
        if (target.equals(leader)) { kicker.sendMessage(Msg.error("You can't kick yourself!")); return; }
        if (!members.contains(target)) { kicker.sendMessage(Msg.error("That player is not in your party!")); return; }
        members.remove(target); pendingInvites.remove(target.getUniqueId());
        target.sendMessage(Msg.box(Component.text("You were kicked from the party by ", NamedTextColor.RED)
                .append(Msg.name(kicker.getName()))));
        broadcast(Msg.name(target.getName()).append(Component.text(" was kicked from the party.", NamedTextColor.RED)));
    }

    public void leave(Player player) {
        if (player.equals(leader)) {
            broadcast(Component.text("The party was disbanded because the leader left.", NamedTextColor.RED));
            disband(); return;
        }
        members.remove(player); pendingInvites.remove(player.getUniqueId());
        player.sendMessage(Msg.info("You left the party."));
        broadcast(Msg.name(player.getName()).append(Component.text(" left the party.", NamedTextColor.RED)));
    }

    public void disband() {
        Component msg = Msg.box(Component.text("The party has been disbanded!", NamedTextColor.RED, TextDecoration.BOLD));
        for (Player m : getOnlineMembers()) { m.sendMessage(msg); plugin.getPartyManager().removeParty(m); }
        members.clear(); pendingInvites.clear();
    }

    public void broadcast(Component message) {
        for (Player m : getOnlineMembers()) m.sendMessage(Msg.partyMsg(message));
    }

    public void setPrivate(boolean b) {
        this.isPrivate = b;
        broadcast(Component.text("Party is now ", NamedTextColor.GRAY)
                .append(Component.text(b ? "PRIVATE" : "PUBLIC", b ? NamedTextColor.RED : NamedTextColor.GREEN)));
    }

    public void transfer(Player newLeader) {
        if (!members.contains(newLeader)) { leader.sendMessage(Msg.error("That player is not in your party!")); return; }
        members.remove(leader); members.add(0, newLeader); members.remove(newLeader); members.add(leader);
        broadcast(Component.text("Leadership transferred to ", NamedTextColor.GRAY)
                .append(Msg.name(newLeader.getName())));
    }
}