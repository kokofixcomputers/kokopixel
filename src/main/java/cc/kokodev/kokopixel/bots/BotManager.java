package cc.kokodev.kokopixel.bots;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.api.bot.BotController;
import cc.kokodev.kokopixel.api.bot.BotEngine;
import cc.kokodev.kokopixel.api.bot.BotHandle;
import cc.kokodev.kokopixel.api.game.GameInstance;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and lifecycle manager for bot engines.
 *
 * <p>Bots are NMS-backed fake players (packet-only, no real client) that are
 * visible to everyone in the game world — identical to the replay system.
 * Minecraft assigns them a default/random skin since no skin data is provided.
 *
 * <p>Usage example (from within a game plugin):
 * <pre>
 * // Register engine once in onEnable
 * KokoPixel.getInstance().getBotManager().registerEngine(new MyBotEngine(this));
 *
 * // Spawn a bot into a running game
 * BotHandle bot = KokoPixel.getInstance().getBotManager()
 *         .spawnBot("myengine", "Bot_1", game.getWorld().getSpawnLocation(), game);
 * </pre>
 */
public class BotManager {

    private final KokoPixel plugin;

    /** engineId -> engine */
    private final Map<String, BotEngine> engines = new ConcurrentHashMap<>();

    /** botUUID -> active handle */
    private final Map<UUID, BotHandleImpl> activeHandles = new ConcurrentHashMap<>();

    /** shadowStand entity UUID -> bot UUID — for fast damage-event routing */
    private final Map<UUID, UUID> shadowStandToBotId = new ConcurrentHashMap<>();

    /** botUUID -> active controller */
    private final Map<UUID, BotController> activeControllers = new ConcurrentHashMap<>();

    /** botUUID -> tick task */
    private final Map<UUID, BukkitTask> tickTasks = new ConcurrentHashMap<>();

    /** gameId -> set of bot UUIDs in that game */
    private final Map<UUID, Set<UUID>> gameBots = new ConcurrentHashMap<>();

    public BotManager(KokoPixel plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Engine Registry
    // -------------------------------------------------------------------------

    public void registerEngine(BotEngine engine) {
        engines.put(engine.getId(), engine);
        plugin.getLogger().info("[BotManager] Registered bot engine: "
                + engine.getId() + " (" + engine.getDisplayName() + ")");
    }

    public void unregisterEngine(String engineId) {
        engines.remove(engineId.toLowerCase());
    }

    public BotEngine getEngine(String engineId) {
        return engines.get(engineId.toLowerCase());
    }

    public Collection<BotEngine> getEngines() {
        return Collections.unmodifiableCollection(engines.values());
    }

    public Set<String> getEngineIds() {
        return Collections.unmodifiableSet(engines.keySet());
    }

    // -------------------------------------------------------------------------
    // Bot Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Spawns a new packet-based fake bot player into the world at the given location,
     * creates a controller via the named engine, and starts its tick loop.
     *
     * @param engineId the registered engine id
     * @param botName  display name shown above the bot's head (max 16 chars)
     * @param spawnAt  location to spawn at (must have a valid world)
     * @param game     the game this bot belongs to
     * @return the bot handle, or null if the engine is unknown
     */
    public BotHandle spawnBot(String engineId, String botName, Location spawnAt, GameInstance game) {
        BotEngine engine = getEngine(engineId);
        if (engine == null) {
            plugin.getLogger().warning("[BotManager] Unknown engine: " + engineId);
            return null;
        }
        return spawnBot(engine, botName, spawnAt, game);
    }

    public BotHandle spawnBot(BotEngine engine, String botName, Location spawnAt, GameInstance game) {
        // Truncate name to MC limit
        String name = botName.length() > 16 ? botName.substring(0, 16) : botName;

        BotHandleImpl handle = new BotHandleImpl(name, spawnAt, plugin);
        handle.spawnForWorld();

        UUID botId = handle.getUniqueId();
        activeHandles.put(botId, handle);
        gameBots.computeIfAbsent(game.getGameId(), k -> ConcurrentHashMap.newKeySet()).add(botId);

        BotController controller = engine.createController(handle, game);
        activeControllers.put(botId, controller);

        controller.onStart(handle, game);
        startTickTask(engine, botId, handle, controller, game);

        plugin.getLogger().info("[BotManager] Spawned bot '" + name
                + "' in game " + game.getGameId() + " using engine " + engine.getId());
        return handle;
    }

    /** Removes a specific bot, despawning its NMS entity and calling onStop. */
    public void removeBot(UUID botId, GameInstance game) {
        BukkitTask task = tickTasks.remove(botId);
        if (task != null) task.cancel();

        BotController controller = activeControllers.remove(botId);
        BotHandleImpl handle = activeHandles.remove(botId);

        if (handle != null) {
            shadowStandToBotId.remove(handle.getShadowStandUUID());
            handle.despawn();
        }
        if (controller != null && handle != null) controller.onStop(handle, game);

        Set<UUID> bots = gameBots.get(game.getGameId());
        if (bots != null) bots.remove(botId);
    }

    /** Removes all bots from a game. Call this from GameInstanceImpl.reallyEnd(). */
    public void removeAllBots(GameInstance game) {
        Set<UUID> bots = gameBots.remove(game.getGameId());
        if (bots == null) return;
        for (UUID botId : new HashSet<>(bots)) {
            removeBot(botId, game);
        }
    }

    /**
     * Notify that a bot was killed.
     * If the controller returns {@code true} from {@link BotController#onDeath}, the bot stays;
     * the controller is expected to call {@link BotHandle#teleport} to respawn it visually.
     * If it returns {@code false}, the bot is removed.
     */
    public void notifyBotDeath(UUID botId, GameInstance game) {
        BotController controller = activeControllers.get(botId);
        BotHandleImpl handle = activeHandles.get(botId);
        if (controller == null || handle == null) return;
        boolean keep = controller.onDeath(handle, game);
        if (!keep) removeBot(botId, game);
    }

    /**
     * Call this when a real player joins a game world mid-match,
     * so they receive spawn packets for all existing bots.
     */
    public void onPlayerJoinWorld(Player player, UUID gameId) {
        Set<UUID> bots = gameBots.get(gameId);
        if (bots == null) return;
        for (UUID botId : bots) {
            BotHandleImpl handle = activeHandles.get(botId);
            if (handle != null) handle.onPlayerJoinWorld(player);
        }
    }

    public boolean isBot(UUID id) {
        return activeHandles.containsKey(id);
    }

    public Set<UUID> getBotsInGame(UUID gameId) {
        return Collections.unmodifiableSet(gameBots.getOrDefault(gameId, Collections.emptySet()));
    }

    public Optional<BotHandle> getHandle(UUID botId) {
        return Optional.ofNullable(activeHandles.get(botId));
    }

    public Optional<BotController> getController(UUID botId) {
        return Optional.ofNullable(activeControllers.get(botId));
    }

    // -------------------------------------------------------------------------
    // Internal / package-private hooks used by MinigameManager
    // -------------------------------------------------------------------------

    /**
     * Registers a shadow stand entity UUID → bot UUID mapping so the damage handler
     * can resolve the bot from the stand that was hit.
     */
    public void registerShadowStand(UUID standUUID, UUID botId) {
        shadowStandToBotId.put(standUUID, botId);
    }

    /** Returns the bot UUID for a given shadow stand entity UUID, or null. */
    public UUID getBotIdForStand(UUID standUUID) {
        return shadowStandToBotId.get(standUUID);
    }

    /**
     * Registers a pre-constructed handle into the manager's tracking maps before
     * the bot is added to a game. Called by MinigameManager so isBot() returns
     * true when the game's addPlayer fires events.
     */
    public void registerHandleForGame(BotHandleImpl handle, GameInstance game) {
        UUID botId = handle.getUniqueId();
        activeHandles.put(botId, handle);
        shadowStandToBotId.put(handle.getShadowStandUUID(), botId);
        gameBots.computeIfAbsent(game.getGameId(), k -> ConcurrentHashMap.newKeySet()).add(botId);
    }

    /**
     * Stores the controller and starts the tick task.  Called by MinigameManager
     * after addPlayer has been called so the controller has a fully initialised
     * GameInstance to work with.
     */
    public void startController(BotHandleImpl handle, BotController controller,
                                 BotEngine engine, GameInstance game) {
        UUID botId = handle.getUniqueId();
        activeControllers.put(botId, controller);
        startTickTask(engine, botId, handle, controller, game);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void startTickTask(BotEngine engine, UUID botId,
                                BotHandleImpl handle, BotController controller, GameInstance game) {
        long interval = Math.max(1, engine.getTickInterval());
        BukkitTask task = new BukkitRunnable() {
            @Override public void run() {
                if (!activeHandles.containsKey(botId)) { cancel(); return; }
                try {
                    controller.onTick(handle, game);
                } catch (Exception e) {
                    plugin.getLogger().warning("[BotManager] Tick error for bot " + handle.getName()
                            + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, interval, interval);
        tickTasks.put(botId, task);
    }
}
