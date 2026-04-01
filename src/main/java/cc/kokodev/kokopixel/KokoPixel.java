package cc.kokodev.kokopixel;

import cc.kokodev.kokopixel.api.KokoPixelAPI;
import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.bungee.BungeeListener;
import cc.kokodev.kokopixel.commands.*;
import cc.kokodev.kokopixel.listeners.*;
import cc.kokodev.kokopixel.menu.AdminGUI;
import cc.kokodev.kokopixel.menu.GameSelectorMenu;
import cc.kokodev.kokopixel.minigames.MinigameManager;
import cc.kokodev.kokopixel.party.PartyManager;
import cc.kokodev.kokopixel.queue.QueueManager;
import cc.kokodev.kokopixel.ranks.RankManager;
import cc.kokodev.kokopixel.spectator.SpectatorManager;
import cc.kokodev.kokopixel.friends.FriendManager;
import cc.kokodev.kokopixel.replay.ReplayManager;
import cc.kokodev.kokopixel.spectator.SpectatorListener;
import cc.kokodev.kokopixel.world.WorldManager;
import io.papermc.lib.PaperLib;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

public class KokoPixel extends JavaPlugin implements KokoPixelAPI {

    private static KokoPixel instance;
    private boolean isBungeeMode = false;
    private BungeeListener bungeeListener;
    private MinigameManager minigameManager;
    private PartyManager partyManager;
    private QueueManager queueManager;
    private WorldManager worldManager;
    private RankManager rankManager;
    private SpectatorManager spectatorManager;
    private GameSelectorMenu gameSelectorMenu;
    private AdminGUI adminGUI;
    private ReplayManager replayManager;
    private FriendManager friendManager;
    private cc.kokodev.kokopixel.commands.MsgCommand msgCommand;
    private Location lobbySpawn;
    private String serverId;

    @Override
    public void onEnable() {
        instance = this;
        
        try {
            Class.forName("net.md_5.bungee.api.plugin.Plugin");
            isBungeeMode = true;
            enableBungeeMode();
            return;
        } catch (ClassNotFoundException e) {
            isBungeeMode = false;
            enablePaperMode();
        }
    }

    private void enablePaperMode() {
        getLogger().info("Starting KokoPixel in Paper server mode");
        
        cc.kokodev.kokopixel.api.KokoPixelAPIProvider.set(this);
        PaperLib.suggestPaper(this);
        saveDefaultConfig();
        loadConfiguration();
        loadLobbySpawn();
        
        this.minigameManager = new MinigameManager(this);
        this.partyManager = new PartyManager(this);
        this.queueManager = new QueueManager(this);
        this.worldManager = new WorldManager(this);
        this.rankManager = new RankManager(this);
        this.spectatorManager = new SpectatorManager(this);
        this.gameSelectorMenu = new GameSelectorMenu(this);
        this.adminGUI = new AdminGUI(this);
        this.replayManager = new ReplayManager(this);
        this.friendManager = new FriendManager(this);
        this.msgCommand = new cc.kokodev.kokopixel.commands.MsgCommand(this);
        
        getCommand("minigame").setExecutor(new MinigameCommand(this));
        getCommand("party").setExecutor(new PartyCommand(this));
        getCommand("kokopixel").setExecutor(new AdminCommand(this));
        getCommand("lobby").setExecutor(new LobbyCommand(this));
        getCommand("admingui").setExecutor(new AdminGUICommand(this));
        getCommand("replay").setExecutor(new ReplayCommand(this));
        getCommand("friend").setExecutor(new FriendCommand(this));
        getCommand("msg").setExecutor(msgCommand);
        getCommand("r").setExecutor(msgCommand);
        
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new AdvancementListener(this), this);
        getServer().getPluginManager().registerEvents(new TabListener(this), this);
        getServer().getPluginManager().registerEvents(new StatisticsListener(this), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(this), this);
        
        for (org.bukkit.World world : getServer().getWorlds()) {
            world.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false);
        }
        
        if (getConfig().getBoolean("bungee.enabled", false)) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "kokopixel:main");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            this.bungeeListener = new BungeeListener(this);
            getServer().getMessenger().registerIncomingPluginChannel(this, "kokopixel:main", bungeeListener);
            getLogger().info("BungeeCord mode enabled - Server ID: " + serverId);
            startHeartbeat();
        }
        
        getLogger().info("KokoPixel V2 v" + getDescription().getVersion() + " enabled!");
    }

    private void enableBungeeMode() {
        getLogger().info("Starting KokoPixel in BungeeCord proxy mode");
        try {
            cc.kokodev.kokopixel.bungee.BungeePlugin plugin = new cc.kokodev.kokopixel.bungee.BungeePlugin();
            plugin.onEnable();
        } catch (Exception e) {
            getLogger().severe("Failed to start BungeeCord mode: " + e.getMessage());
        }
    }

    private void startHeartbeat() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (queueManager != null) {
                java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream out = new java.io.DataOutputStream(byteOut);
                try {
                    out.writeUTF("HEARTBEAT");
                    out.writeUTF(serverId);
                    out.writeInt(getServer().getOnlinePlayers().size());
                    out.writeInt(queueManager.getTotalQueueSize());
                    getServer().sendPluginMessage(this, "kokopixel:main", byteOut.toByteArray());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // Broadcast replay index so all servers know what replays we hold
            if (bungeeListener != null) {
                getServer().getScheduler().runTask(this,
                        () -> bungeeListener.broadcastReplayIndex());
            }
        }, 20L, 20L);
    }

    private void loadConfiguration() {
        this.serverId = getConfig().getString("server-id", UUID.randomUUID().toString().substring(0, 8));
        if (!getConfig().contains("server-id")) {
            getConfig().set("server-id", serverId);
            saveConfig();
        }
    }

    private void loadLobbySpawn() {
        File lobbyFile = new File(getDataFolder(), "lobby.yml");
        if (lobbyFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(lobbyFile);
            String worldName = config.getString("world");
            if (worldName != null && getServer().getWorld(worldName) != null) {
                double x = config.getDouble("x");
                double y = config.getDouble("y");
                double z = config.getDouble("z");
                float yaw = (float) config.getDouble("yaw");
                float pitch = (float) config.getDouble("pitch");
                lobbySpawn = new Location(getServer().getWorld(worldName), x, y, z, yaw, pitch);
            }
        }
        if (lobbySpawn == null && !getServer().getWorlds().isEmpty()) {
            lobbySpawn = getServer().getWorlds().get(0).getSpawnLocation();
        }
    }

    @Override
    public void onDisable() {
        if (isBungeeMode) return;
        if (minigameManager != null) minigameManager.shutdown();
        if (worldManager != null) worldManager.cleanup();
        if (partyManager != null) partyManager.disbandAll();
        if (spectatorManager != null) spectatorManager.cleanup();
        getLogger().info("KokoPixel V2 disabled!");
    }

    public static KokoPixel getInstance() { return instance; }
    public boolean isBungeeMode() { return isBungeeMode; }
    public boolean isBungeeEnabled() { return isBungeeMode; }
    public BungeeListener getBungeeListener() { return bungeeListener; }
    public MinigameManager getMinigameManager() { return minigameManager; }
    public PartyManager getPartyManager() { return partyManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public WorldManager getWorldManager() { return worldManager; }
    public RankManager getRankManager() { return rankManager; }
    public SpectatorManager getSpectatorManager() { return spectatorManager; }
    public GameSelectorMenu getGameSelectorMenu() { return gameSelectorMenu; }
    public AdminGUI getAdminGUI() { return adminGUI; }
    public ReplayManager getReplayManager() { return replayManager; }
    public FriendManager getFriendManager() { return friendManager; }
    public cc.kokodev.kokopixel.commands.MsgCommand getMsgCommand() { return msgCommand; }
    public Location getLobbySpawn() { return lobbySpawn.clone(); }
    public void setLobbySpawn(Location location) { this.lobbySpawn = location.clone(); }
    public String getServerId() { return serverId; }
    
    @Override public Optional<GameInstance> getGame(org.bukkit.entity.Player player) { return minigameManager.getGame(player).map(g -> (GameInstance) g); }
    @Override public Optional<GameInstance> getGame(UUID playerId) { return minigameManager.getGame(playerId).map(g -> (GameInstance) g); }
    @Override public boolean isInGame(org.bukkit.entity.Player player) { return minigameManager.isInGame(player); }
    @Override public void joinQueue(org.bukkit.entity.Player player, String gameType) { queueManager.addToQueue(player, minigameManager.getMinigame(gameType)); }
    @Override public void leaveQueue(org.bukkit.entity.Player player) { queueManager.removeFromQueue(player); }
    @Override public boolean isInQueue(org.bukkit.entity.Player player) { return queueManager.isInQueue(player); }
    @Override public void enableSpectator(org.bukkit.entity.Player player) { spectatorManager.enableSpectator(player, true); }
    @Override public void enableSpectator(org.bukkit.entity.Player player, boolean canReturnToLobby) { spectatorManager.enableSpectator(player, canReturnToLobby); }
    @Override public void disableSpectator(org.bukkit.entity.Player player) { spectatorManager.disableSpectator(player); }
    @Override public boolean isSpectating(org.bukkit.entity.Player player) { return spectatorManager.isSpectator(player); }
    @Override public void teleportToPlayer(org.bukkit.entity.Player spectator, org.bukkit.entity.Player target) { spectatorManager.teleportToPlayer(spectator, target); }
    @Override public String getCurrentServerId() { return serverId; }
    @Override public void setGameSelectorBlock(org.bukkit.Location location, String gameType) { gameSelectorMenu.registerGameSelectorBlock(location, gameType); }
    @Override public void removeGameSelectorBlock(org.bukkit.Location location) { gameSelectorMenu.removeGameSelectorBlock(location); }
    @Override public void sendGameMessage(UUID gameId, String message) { minigameManager.getGameInstance(gameId).ifPresent(game -> game.broadcast(message)); }
    @Override public Optional<Integer> getPlayerStat(UUID playerId, UUID gameId, String stat) { return minigameManager.getGameInstance(gameId).map(game -> game.getStat(playerId, stat)); }
}