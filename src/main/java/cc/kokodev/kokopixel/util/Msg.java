package cc.kokodev.kokopixel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/**
 * Central factory for all themed server messages.
 *
 * Theme:
 *   ─────────────────────────────  (bold blue divider)
 *   [content line]
 *   ─────────────────────────────
 *
 * Inline messages (no divider) use the same colour palette.
 */
public final class Msg {

    private static final String DIVIDER_STR = "─────────────────────────────";
    private static final NamedTextColor DIVIDER_COLOR = NamedTextColor.BLUE;
    private static final NamedTextColor LABEL_COLOR   = NamedTextColor.AQUA;
    private static final NamedTextColor BODY_COLOR    = NamedTextColor.GRAY;
    private static final NamedTextColor ACCENT_COLOR  = NamedTextColor.YELLOW;
    private static final NamedTextColor ACTION_COLOR  = NamedTextColor.GREEN;
    private static final NamedTextColor ERROR_COLOR   = NamedTextColor.RED;
    private static final NamedTextColor NAME_COLOR    = NamedTextColor.WHITE;

    private Msg() {}

    // -------------------------------------------------------------------------
    // Divider
    // -------------------------------------------------------------------------

    public static Component divider() {
        return Component.text(DIVIDER_STR, DIVIDER_COLOR, TextDecoration.BOLD);
    }

    // -------------------------------------------------------------------------
    // Boxed message (divider + content + divider)
    // -------------------------------------------------------------------------

    public static Component box(Component... lines) {
        Component result = divider();
        for (Component line : lines) result = result.append(Component.newline()).append(line);
        return result.append(Component.newline()).append(divider());
    }

    // -------------------------------------------------------------------------
    // Common inline builders
    // -------------------------------------------------------------------------

    public static Component error(String text) {
        return Component.text("✗ ", ERROR_COLOR, TextDecoration.BOLD)
                .append(Component.text(text, ERROR_COLOR));
    }

    public static Component success(String text) {
        return Component.text("✔ ", ACTION_COLOR, TextDecoration.BOLD)
                .append(Component.text(text, ACTION_COLOR));
    }

    public static Component info(String text) {
        return Component.text("• ", LABEL_COLOR)
                .append(Component.text(text, BODY_COLOR));
    }

    public static Component label(String label, String value) {
        return Component.text(label + ": ", LABEL_COLOR)
                .append(Component.text(value, NAME_COLOR));
    }

    public static Component name(String playerName) {
        return Component.text(playerName, NAME_COLOR, TextDecoration.BOLD);
    }

    public static Component accent(String text) {
        return Component.text(text, ACCENT_COLOR);
    }

    // -------------------------------------------------------------------------
    // Clickable action button
    // -------------------------------------------------------------------------

    public static Component clickable(String label, String command, String hoverText) {
        return Component.text("[", BODY_COLOR)
                .append(Component.text(label, ACTION_COLOR, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, BODY_COLOR))))
                .append(Component.text("]", BODY_COLOR));
    }

    public static Component clickableSuggest(String label, String command, String hoverText) {
        return Component.text("[", BODY_COLOR)
                .append(Component.text(label, ACCENT_COLOR, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, BODY_COLOR))))
                .append(Component.text("]", BODY_COLOR));
    }

    // -------------------------------------------------------------------------
    // Pre-built themed messages
    // -------------------------------------------------------------------------

    /** Party invite notification sent to the target. */
    public static Component partyInvite(String inviterName) {
        return box(
            name(inviterName).append(Component.text(" has invited you to their party!", ACCENT_COLOR)),
            clickable("Click here to accept", "/party accept " + inviterName, "Accept the party invite")
        );
    }

    /** Party broadcast wrapper. */
    public static Component partyMsg(Component content) {
        return Component.text("[Party] ", LABEL_COLOR, TextDecoration.BOLD).append(content);
    }

    /** Direct message received. */
    public static Component dmReceived(String fromName, String message) {
        return Component.text("[", BODY_COLOR)
                .append(Component.text(fromName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(" → You", BODY_COLOR))
                .append(Component.text("] ", BODY_COLOR))
                .append(Component.text(message, NAME_COLOR));
    }

    /** Direct message sent. */
    public static Component dmSent(String toName, String message) {
        return Component.text("[You → ", BODY_COLOR)
                .append(Component.text(toName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text("] ", BODY_COLOR))
                .append(Component.text(message, NAME_COLOR));
    }

    /** Friend request notification. */
    public static Component friendRequest(String fromName) {
        return box(
            name(fromName).append(Component.text(" sent you a friend request!", ACCENT_COLOR)),
            clickable("Accept", "/friend accept " + fromName, "Accept friend request")
                .append(Component.text("  ", BODY_COLOR))
                .append(Component.text("[", BODY_COLOR)
                    .append(Component.text("Deny", ERROR_COLOR, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/friend deny " + fromName))
                        .hoverEvent(HoverEvent.showText(Component.text("Deny friend request", BODY_COLOR))))
                    .append(Component.text("]", BODY_COLOR)))
        );
    }

    /** Friend came online. */
    public static Component friendOnline(String name, String server) {
        return Component.text("→ ", ACTION_COLOR, TextDecoration.BOLD)
                .append(name(name))
                .append(Component.text(" is now online", ACTION_COLOR))
                .append(Component.text(" on " + server, BODY_COLOR));
    }

    /** Friend went offline. */
    public static Component friendOffline(String name) {
        return Component.text("← ", ERROR_COLOR, TextDecoration.BOLD)
                .append(name(name))
                .append(Component.text(" went offline.", BODY_COLOR));
    }

    // -------------------------------------------------------------------------
    // Send helpers
    // -------------------------------------------------------------------------

    public static void send(CommandSender target, Component msg) { target.sendMessage(msg); }
    public static void sendError(CommandSender target, String text) { target.sendMessage(error(text)); }
    public static void sendSuccess(CommandSender target, String text) { target.sendMessage(success(text)); }
    public static void sendInfo(CommandSender target, String text) { target.sendMessage(info(text)); }
}
