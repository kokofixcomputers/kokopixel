package cc.kokodev.kokopixel.minigames;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.api.game.GameInstance;
import cc.kokodev.kokopixel.api.game.GamePlayer;
import cc.kokodev.kokopixel.api.game.GameState;
import cc.kokodev.kokopixel.api.game.GameTeam;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class GameInstanceImpl implements GameInstance {
    protected final UUID gameId;
    protected final Minigame minigame;
    protected final World world;
    protected final JavaPlugin plugin;
    protected final List<GamePlayerImpl> players = new CopyOnWriteArrayList<>();
    protected final List<GameTeamImpl> teams = new CopyOnWriteArrayList<>();
    protected final Map<UUID, GameTeamImpl> playerTeams = new ConcurrentHashMap<>();
    protected final Map<UUID, Map<String, Integer>> stats = new ConcurrentHashMap<>();
    protected final Map<String, Object> data = new HashMap<>();
    protected final List<Location> spawnPoints = new ArrayList<>();
    protected final Map<String, List<Location>> teamSpawnPoints = new HashMap<>();
    protected GameState state = GameState.WAITING;
    protected List<BukkitTask> tasks = new ArrayList<>();
    protected boolean isPrivate = false;

    public GameInstanceImpl(Minigame minigame, World world, JavaPlugin plugin) {
        this.gameId = UUID.randomUUID();
        this.minigame = minigame;
        this.world = world;
        this.plugin = plugin;
        if (minigame.supportsTeams()) for (String name : minigame.getTeams()) teams.add(new GameTeamImpl(name));
    }

    @Override public UUID getGameId() { return gameId; }
    @Override public String getGameType() { return minigame.getName(); }
    @Override public GameState getState() { return state; }
    @Override public World getWorld() { return world; }
    @Override public List<GamePlayer> getPlayers() { return new ArrayList<>(players); }
    @Override public Optional<GamePlayer> getPlayer(UUID id) { return players.stream().filter(p -> p.getUniqueId().equals(id)).map(p -> (GamePlayer) p).findFirst(); }
    @Override public List<GameTeam> getTeams() { return new ArrayList<>(teams); }
    @Override public Optional<GameTeam> getTeam(String name) { return teams.stream().filter(t -> t.getName().equalsIgnoreCase(name)).map(t -> (GameTeam) t).findFirst(); }
    @Override public Optional<GameTeam> getPlayerTeam(UUID id) { return Optional.ofNullable(playerTeams.get(id)); }
    @Override public void broadcast(String msg) { for (GamePlayer p : players) p.getPlayer().sendMessage(Component.text("[" + minigame.getDisplayName() + "] ", NamedTextColor.GOLD).append(Component.text(msg, NamedTextColor.WHITE))); }
    @Override public void broadcastTitle(String t, String s, int fi, int st, int fo) { Title.Times times = Title.Times.times(Duration.ofMillis(fi * 50L), Duration.ofMillis(st * 50L), Duration.ofMillis(fo * 50L)); for (GamePlayer p : players) p.getPlayer().showTitle(Title.title(Component.text(t), Component.text(s), times)); }
    @Override public void setStat(UUID id, String key, int v) { stats.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(key, v); }
    @Override public void incrementStat(UUID id, String key, int a) { stats.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).merge(key, a, Integer::sum); }
    @Override public int getStat(UUID id, String key) { return stats.getOrDefault(id, Collections.emptyMap()).getOrDefault(key, 0); }
    @Override public Map<String, Integer> getStats(UUID id) { return new HashMap<>(stats.getOrDefault(id, Collections.emptyMap())); }
    @Override public void teleport(Player p, Location l) { p.teleport(l); }
    @Override public void teleportToSpawn(Player p) { if (!spawnPoints.isEmpty()) p.teleport(spawnPoints.get(new Random().nextInt(spawnPoints.size()))); else p.teleport(world.getSpawnLocation()); }
    @Override public void teleportToTeamSpawn(Player p, String team) { List<Location> list = teamSpawnPoints.get(team); if (list != null && !list.isEmpty()) p.teleport(list.get(new Random().nextInt(list.size()))); else teleportToSpawn(p); }
    @Override public void setSpawnPoint(Location l) { spawnPoints.add(l.clone()); }
    @Override public void setTeamSpawnPoint(String team, Location l) { teamSpawnPoints.computeIfAbsent(team, k -> new ArrayList<>()).add(l.clone()); }
    public void addSpawnPoint(Location l) { spawnPoints.add(l.clone()); }
    public void addTeamSpawnPoint(String team, Location l) { teamSpawnPoints.computeIfAbsent(team, k -> new ArrayList<>()).add(l.clone()); }
    @Override public boolean isPrivate() { return isPrivate; }
    @Override public void setPrivate(boolean b) { this.isPrivate = b; }

    @Override
    public void start() {
        // Start replay recording
        KokoPixel.getInstance().getReplayManager().startRecording(this);

        if (isPrivate) { state = GameState.STARTING; onGameStart(); state = GameState.ACTIVE; }
        else {
            state = GameState.COUNTDOWN;
            new BukkitRunnable() {
                int countdown = 5;
                @Override public void run() {
                    if (state != GameState.COUNTDOWN) { cancel(); return; }
                    if (countdown == 0) { state = GameState.STARTING; onGameStart(); state = GameState.ACTIVE; cancel(); }
                    else { broadcastTitle("§e" + countdown, "Get ready!", 5, 15, 5); broadcast("§eGame starting in " + countdown + " seconds..."); countdown--; }
                }
            }.runTaskTimer(plugin, 0L, 20L);
        }
    }

    @Override
    public void end(List<UUID> winners) {
        if (state == GameState.ENDED) return;
        state = GameState.ENDING;
        if (!winners.isEmpty()) {
            if (winners.size() == 1) getPlayer(winners.get(0)).ifPresent(p -> broadcast("§6§lWinner: §e" + p.getName()));
            else { StringBuilder sb = new StringBuilder("§6§lWinners: §e"); for (UUID id : winners) getPlayer(id).ifPresent(p -> { if (sb.length() > 15) sb.append(", "); sb.append(p.getName()); }); broadcast(sb.toString()); }
        }
        onGameEnd(winners);
        new BukkitRunnable() { @Override public void run() { reallyEnd(); } }.runTaskLater(plugin, 60L);
    }

    @Override public void end() { end(new ArrayList<>()); }

    private void reallyEnd() {
        state = GameState.ENDED;
        // Stop replay recording before clearing players
        KokoPixel.getInstance().getReplayManager().stopRecording(this);
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        KokoPixel kp = KokoPixel.getInstance();
        for (GamePlayerImpl p : new ArrayList<>(players)) {
            Player bp = p.getPlayer();
            // Remove from manager's playerGames map first (avoids double-removal loop)
            kp.getMinigameManager().clearPlayerGame(bp.getUniqueId());
            if (bp != null && bp.isOnline()) {
                bp.setGameMode(org.bukkit.GameMode.ADVENTURE);
                bp.setHealth(20); bp.setFoodLevel(20); bp.setSaturation(10);
                bp.getActivePotionEffects().forEach(e -> bp.removePotionEffect(e.getType()));
                bp.getInventory().clear();
                kp.getGameSelectorMenu().giveGameSelector(bp);
                bp.teleport(kp.getLobbySpawn());
            }
        }
        players.clear(); playerTeams.clear();
        minigame.removeGame(this);
        kp.getWorldManager().deleteWorld(world);
        kp.getMinigameManager().cleanupGame(gameId);
    }

    @Override public void runTaskLater(Runnable r, long d) { tasks.add(new BukkitRunnable() { @Override public void run() { if (state != GameState.ENDED) r.run(); } }.runTaskLater(plugin, d)); }
    @Override public void runTaskTimer(Runnable r, long d, long p) { tasks.add(new BukkitRunnable() { @Override public void run() { if (state != GameState.ENDED) r.run(); } }.runTaskTimer(plugin, d, p)); }
    @Override public JavaPlugin getPlugin() { return plugin; }
    @Override public Map<String, Object> getData() { return data; }
    public Minigame getMinigame() { return minigame; }

    public void addPlayer(Player p) {
        GamePlayerImpl gp = new GamePlayerImpl(p, this);
        players.add(gp);
        gp.saveInventory();
        gp.clearEffects();
        p.teleport(world.getSpawnLocation());
        if (!teams.isEmpty()) assignPlayerToTeam(gp);
        onPlayerJoin(gp);
    }

    public void addPlayers(List<Player> list) { list.forEach(this::addPlayer); }

    private void assignPlayerToTeam(GamePlayerImpl p) {
        GameTeamImpl team = teams.stream().min(Comparator.comparingInt(t -> t.getMemberCount())).orElse(null);
        if (team != null) { team.addMember(p); playerTeams.put(p.getUniqueId(), team); p.setTeam(team); }
    }

    public void removePlayer(Player p) {
        players.removeIf(gp -> {
            if (gp.getUniqueId().equals(p.getUniqueId())) {
                GameTeamImpl team = playerTeams.remove(p.getUniqueId());
                if (team != null) team.removeMember(gp);
                onPlayerLeave(gp);
                // Only restore state/teleport to lobby if the minigame doesn't handle death itself
                if (p.isOnline() && !minigame.handlesDeath()) {
                    p.setGameMode(org.bukkit.GameMode.ADVENTURE);
                    p.setHealth(20); p.setFoodLevel(20); p.setSaturation(10);
                    p.getActivePotionEffects().clear();
                    p.getInventory().clear();
                    KokoPixel.getInstance().getGameSelectorMenu().giveGameSelector(p);
                    p.teleport(KokoPixel.getInstance().getLobbySpawn());
                }
                return true;
            }
            return false;
        });
        if (players.isEmpty() && state == GameState.ACTIVE) end();
    }

    protected abstract void onGameStart();
    protected abstract void onGameEnd(List<UUID> winners);
    protected abstract void onPlayerJoin(GamePlayerImpl player);
    protected abstract void onPlayerLeave(GamePlayerImpl player);
}