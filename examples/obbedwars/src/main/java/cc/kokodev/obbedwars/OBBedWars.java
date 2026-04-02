package cc.kokodev.obbedwars;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.plugin.java.JavaPlugin;

public class OBBedWars extends JavaPlugin {

    private static OBBedWars instance;
    private BedWarsMinigame minigame;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Wait one tick for KokoPixel to finish loading
        getServer().getScheduler().runTaskLater(this, () -> {
            minigame = new BedWarsMinigame(this);
            KokoPixel.getInstance().getMinigameManager().registerMinigame(minigame);
            getLogger().info("OBBedWars registered with KokoPixel.");
        }, 1L);

        getCommand("obbedwars").setExecutor(new BedWarsSetupCommand(this));
        getServer().getPluginManager().registerEvents(new BedWarsListener(this), this);
    }

    @Override
    public void onDisable() {
        getLogger().info("OBBedWars disabled.");
    }

    public static OBBedWars getInstance() { return instance; }
    public BedWarsMinigame getMinigame() { return minigame; }
}
