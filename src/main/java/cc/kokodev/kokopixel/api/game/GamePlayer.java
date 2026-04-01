package cc.kokodev.kokopixel.api.game;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GamePlayer {
    Player getPlayer();
    UUID getUniqueId();
    String getName();
    boolean isInGame();
    Optional<GameTeam> getTeam();
    void setTeam(GameTeam team);
    int getKills();
    int getDeaths();
    void addKill();
    void addDeath();
    boolean isAlive();
    void setAlive(boolean alive);
    int getStat(String key);
    void setStat(String key, int value);
    void incrementStat(String key);
    Map<String, Integer> getStats();
    void resetInventory();
    void saveInventory();
    void giveItems(ItemStack... items);
    void addEffects(PotionEffect... effects);
    void clearEffects();
}