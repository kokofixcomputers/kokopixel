package cc.kokodev.kokopixel.ranks;

import org.bukkit.ChatColor;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class Rank implements ConfigurationSerializable {
    private final String name, prefix, displayName;
    private final ChatColor color;
    private final int priority;
    private final boolean isDefault;

    public Rank(String name, String prefix, String displayName, ChatColor color, int priority, boolean isDefault) {
        this.name = name; this.prefix = prefix; this.displayName = displayName; this.color = color; this.priority = priority; this.isDefault = isDefault;
    }
    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public String getDisplayName() { return displayName; }
    public ChatColor getColor() { return color; }
    public int getPriority() { return priority; }
    public boolean isDefault() { return isDefault; }
    public String getFormattedPrefix() { return ChatColor.translateAlternateColorCodes('&', prefix) + "&r"; }
    public String getFormattedName() { return color + displayName + ChatColor.RESET; }
    public String getFullFormat() { return getFormattedPrefix() + " " + getFormattedName(); }

    @Override public @NotNull Map<String, Object> serialize() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name); m.put("prefix", prefix); m.put("displayName", displayName); m.put("color", color.name()); m.put("priority", priority); m.put("default", isDefault);
        return m;
    }
    public static Rank deserialize(Map<String, Object> m) { return new Rank((String)m.get("name"), (String)m.get("prefix"), (String)m.get("displayName"), ChatColor.valueOf((String)m.get("color")), (int)m.get("priority"), (boolean)m.get("default")); }
}