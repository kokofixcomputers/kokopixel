package cc.kokodev.kokopixel.minigames;

import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameTeam;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class GamePlayerImpl implements GamePlayer {
    private final Player player;
    private final GameInstanceImpl game;
    private final Map<String, Integer> customStats = new HashMap<>();
    private ItemStack[] savedInventory;
    private ItemStack[] savedArmor;
    private GameTeam team;
    private boolean alive = true;
    private int kills = 0, deaths = 0;

    public GamePlayerImpl(Player player, GameInstanceImpl game) { this.player = player; this.game = game; }
    @Override public Player getPlayer() { return player; }
    @Override public UUID getUniqueId() { return player.getUniqueId(); }
    @Override public String getName() { return player.getName(); }
    @Override public boolean isInGame() { return game.getPlayers().contains(this); }
    @Override public Optional<GameTeam> getTeam() { return Optional.ofNullable(team); }
    @Override public void setTeam(GameTeam t) { this.team = t; }
    @Override public int getKills() { return kills; }
    @Override public int getDeaths() { return deaths; }
    @Override public void addKill() { kills++; game.incrementStat(player.getUniqueId(), "kills", 1); }
    @Override public void addDeath() { deaths++; game.incrementStat(player.getUniqueId(), "deaths", 1); }
    @Override public boolean isAlive() { return alive; }
    @Override public void setAlive(boolean a) { this.alive = a; if (!a) player.setGameMode(GameMode.SPECTATOR); }
    @Override public int getStat(String key) { return customStats.getOrDefault(key, 0); }
    @Override public void setStat(String key, int v) { customStats.put(key, v); game.setStat(player.getUniqueId(), key, v); }
    @Override public void incrementStat(String key) { customStats.merge(key, 1, Integer::sum); game.incrementStat(player.getUniqueId(), key, 1); }
    @Override public Map<String, Integer> getStats() { return new HashMap<>(customStats); }
    @Override public void resetInventory() { if (savedInventory != null) { player.getInventory().setContents(savedInventory); player.getInventory().setArmorContents(savedArmor); } }
    @Override public void saveInventory() { PlayerInventory inv = player.getInventory(); savedInventory = inv.getContents(); savedArmor = inv.getArmorContents(); }
    @Override public void giveItems(ItemStack... items) { player.getInventory().clear(); for (ItemStack i : items) player.getInventory().addItem(i); }
    @Override public void addEffects(PotionEffect... effects) { for (PotionEffect e : effects) player.addPotionEffect(e); }
    @Override public void clearEffects() { player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType())); }
}