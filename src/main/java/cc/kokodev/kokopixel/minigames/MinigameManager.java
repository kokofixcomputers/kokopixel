package cc.kokodev.kokopixel.minigames;

import cc.kokodev.kokopixel.KokoPixel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MinigameManager {
    private final KokoPixel plugin;
    private final Map<String, Minigame> minigames = new ConcurrentHashMap<>();
    private final Map<UUID, GameInstanceImpl> activeGames = new ConcurrentHashMap<>();
    private final Map<UUID, GameInstanceImpl> playerGames = new ConcurrentHashMap<>();
    /** world name -> gameId, for routing block events to the recorder */
    private final Map<String, UUID> worldGames = new ConcurrentHashMap<>();
    private final File minigamesFolder;

    public MinigameManager(KokoPixel plugin) {
        this.plugin = plugin;
        this.minigamesFolder = new File(plugin.getDataFolder(), "minigames");
        if (!minigamesFolder.exists()) minigamesFolder.mkdirs();
    }

    public void registerMinigame(Minigame minigame) { minigames.put(minigame.getName().toLowerCase(), minigame); plugin.getLogger().info("Registered minigame: " + minigame.getName()); }
    public Minigame getMinigame(String name) { return minigames.get(name.toLowerCase()); }
    public Set<String> getMinigameNames() { return minigames.keySet(); }
    public Collection<Minigame> getMinigames() { return minigames.values(); }

    public void setTemplateWorld(String name, World w) {
        Minigame m = getMinigame(name);
        if (m != null) { m.setTemplateWorld(w); plugin.getLogger().info("Set template world for " + name + " to " + w.getName()); }
    }

    public void addSpawnPoint(String name, Location l) { Minigame m = getMinigame(name); if (m != null) m.addSpawnPoint(l); }
    public void addTeamSpawnPoint(String name, String team, Location l) { Minigame m = getMinigame(name); if (m != null) m.addTeamSpawnPoint(team, l); }

    public void startGame(Minigame mg, List<Player> players, boolean isPrivate) {
        startGame(mg, players, isPrivate, java.util.Collections.emptyList());
    }

    public void startGame(Minigame mg, List<Player> players, boolean isPrivate,
                          List<cc.kokodev.kokopixel.party.Party.BotSlot> botSlots) {
        plugin.getWorldManager().createGameWorldAsync(mg, w -> {
            if (w == null) { players.forEach(p -> p.sendMessage("§cFailed to create game world!")); return; }
            GameInstanceImpl game = mg.createInstance(w);
            if (game == null) { players.forEach(p -> p.sendMessage("§cFailed to start game!")); plugin.getWorldManager().deleteWorld(w); return; }
            game.setPrivate(isPrivate);
            activeGames.put(game.getGameId(), game);
            worldGames.put(w.getName(), game.getGameId());
            mg.addGame(game);
            for (Player p : players) {
                game.addPlayer(p);
                playerGames.put(p.getUniqueId(), game);
                plugin.getQueueManager().silentRemove(p);
            }
            game.start();
            // Inject bots after the game has started
            if (!botSlots.isEmpty()) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                        injectBots(game, botSlots), 2L);
            }
        });
    }

    private void injectBots(GameInstanceImpl game, List<cc.kokodev.kokopixel.party.Party.BotSlot> slots) {
        cc.kokodev.kokopixel.bots.BotManager bm = plugin.getBotManager();
        int botIndex = 1;
        for (cc.kokodev.kokopixel.party.Party.BotSlot slot : slots) {
            cc.kokodev.kokopixel.api.bot.BotEngine engine = bm.getEngine(slot.engineId());
            if (engine == null) {
                plugin.getLogger().warning("[BotManager] Cannot inject bots — unknown engine: " + slot.engineId());
                continue;
            }
            for (int i = 0; i < slot.count(); i++) {
                String name = "Bot_" + botIndex++;
                org.bukkit.Location spawnAt = game.getWorld().getSpawnLocation();
                // Spawn the NMS fake player and get its ServerPlayer so we can addPlayer to game
                cc.kokodev.kokopixel.bots.BotHandleImpl handle =
                        new cc.kokodev.kokopixel.bots.BotHandleImpl(name, spawnAt, plugin);

                // Register with BotManager tracking maps BEFORE addPlayer so isBot() works
                bm.registerHandleForGame(handle, game);

                // Add the NMS ServerPlayer as a real player to the GameInstance.
                // getBukkitEntity() returns the CraftPlayer wrapping the ServerPlayer,
                // so the game treats it as a normal Player with no code changes needed.
                Player botBukkitPlayer = handle.getBukkitPlayer();
                game.addPlayer(botBukkitPlayer);
                playerGames.put(botBukkitPlayer.getUniqueId(), game);

                // Now spawn the visual entity for all viewers and start AI
                handle.spawnForWorld();
                cc.kokodev.kokopixel.api.bot.BotController controller = engine.createController(handle, game);
                // Register shadow stand UUID → bot UUID BEFORE starting tick task
                bm.registerShadowStand(handle.getShadowStandUUID(), handle.getUniqueId());
                // onStart before tick task so the goal stack is populated before first tick
                controller.onStart(handle, game);
                bm.startController(handle, controller, engine, game);
            }
        }
    }

    public Optional<GameInstanceImpl> getGame(Player p) { return Optional.ofNullable(playerGames.get(p.getUniqueId())); }
    public Optional<GameInstanceImpl> getGame(UUID id) { return Optional.ofNullable(playerGames.get(id)); }
    public Optional<GameInstanceImpl> getGameInstance(UUID id) { return Optional.ofNullable(activeGames.get(id)); }
    public boolean isInGame(Player p) { return playerGames.containsKey(p.getUniqueId()); }
    public Collection<GameInstanceImpl> getActiveGames() { return activeGames.values(); }

    public void removePlayer(Player p) {
        GameInstanceImpl game = playerGames.remove(p.getUniqueId());
        if (game != null) {
            game.removePlayer(p);
            // If the game ended as a result, clean it from activeGames
            if (game.getState() == cc.kokodev.kokopixel.api.game.GameState.ENDED
                    || game.getPlayers().isEmpty()) {
                activeGames.remove(game.getGameId());
            }
        }
    }

    /** Called by GameInstanceImpl.reallyEnd() to clear a player's game entry without triggering removePlayer on the game again. */
    public void clearPlayerGame(UUID playerId) {
        playerGames.remove(playerId);
    }

    /** Called by GameInstanceImpl.reallyEnd() to remove the game from activeGames after it has fully ended. */
    public void cleanupGame(UUID gameId) {
        GameInstanceImpl game = activeGames.remove(gameId);
        if (game != null) worldGames.remove(game.getWorld().getName());
    }

    /** Returns the gameId for the world, if it's an active game world. */
    public Optional<UUID> getGameIdForWorld(String worldName) {
        return Optional.ofNullable(worldGames.get(worldName));
    }

    /** Called when a remote server times out — removes game entries for any player not currently online on this server. */
    public void clearStaleOfflinePlayers() {
        playerGames.entrySet().removeIf(entry -> {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            return p == null || !p.isOnline();
        });
    }

    public void removeMinigamesByPlugin(Plugin p) {
        List<String> toRemove = new ArrayList<>();
        for (Minigame m : minigames.values()) if (m.getPlugin().equals(p)) toRemove.add(m.getName());
        for (String name : toRemove) minigames.remove(name);
    }

    public void shutdown() { for (GameInstanceImpl g : activeGames.values()) g.end(); activeGames.clear(); playerGames.clear(); }
}