# KokoPixel Developer Documentation

Guide for building a minigame plugin on top of KokoPixel.

## Table of Contents

- [Overview](#overview)
- [Setup](#setup)
- [Creating a Minigame](#creating-a-minigame)
- [Creating a Game Instance](#creating-a-game-instance)
- [Teams](#teams)
- [Statistics](#statistics)
- [Scheduled Tasks](#scheduled-tasks)
- [Game Lifecycle](#game-lifecycle)
- [Bukkit Events](#bukkit-events)
- [KokoPixelAPI Reference](#kokopixelapi-reference)
- [GameInstance Reference](#gameinstance-reference)
- [GamePlayer Reference](#gameplayer-reference)
- [GameTeam Reference](#gameteam-reference)
- [GameState Reference](#gamestate-reference)
- [Full Example](#full-example)

---

## Overview

KokoPixel handles everything outside your game logic:

- World cloning and cleanup
- Queue management (public, private, cross-server)
- Party routing
- Spectating and replays
- Player state save/restore on join and leave

You provide two classes:

1. A `Minigame` subclass — describes your game (name, player counts, teams)
2. A `GameInstanceImpl` subclass — the actual game logic for one running match

---

## Setup

Add KokoPixel as a dependency in your plugin. Since it's a local project, add it to your `build.gradle`:

```groovy
dependencies {
    compileOnly files('../kokopixel/build/libs/KokoPixel.jar')
}
```

Declare the dependency in your `plugin.yml`:

```yaml
depend: [KokoPixel]
```

---

## Creating a Minigame

Extend `cc.kokodev.kokopixel.minigames.Minigame` and pass your `GameInstanceImpl` class to the super constructor. Register it with KokoPixel in your plugin's `onEnable`.

```java
public class SkyWars extends Minigame {

    public SkyWars(JavaPlugin plugin) {
        super(
            "skywars",          // internal name — used in commands and config
            "&bSky&fWars",      // display name — supports & colour codes
            2,                  // min players
            12,                 // max players
            plugin,
            SkyWarsGame.class   // your GameInstanceImpl subclass
        );
    }
}
```

Register it in `onEnable`:

```java
@Override
public void onEnable() {
    // Wait for KokoPixel to finish loading first
    Bukkit.getScheduler().runTaskLater(this, () -> {
        KokoPixelAPI api = KokoPixelAPI.get();
        MinigameManager mgr = KokoPixel.getInstance().getMinigameManager();
        mgr.registerMinigame(new SkyWars(this));
    }, 1L);
}
```

> The 1-tick delay ensures KokoPixel has fully initialised before you register.

---

## Creating a Game Instance

Extend `cc.kokodev.kokopixel.minigames.GameInstanceImpl`. The constructor signature must match exactly — KokoPixel instantiates it via reflection.

```java
public class SkyWarsGame extends GameInstanceImpl {

    public SkyWarsGame(Minigame minigame, World world, JavaPlugin plugin) {
        super(minigame, world, plugin);
    }

    @Override
    protected void onGameStart() {
        // Called once when the game transitions to ACTIVE.
        // Players are already teleported to the world's spawn.
        // Give them their kits here.
        for (GamePlayer gp : getPlayers()) {
            gp.giveItems(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.BREAD, 5)
            );
            teleportToSpawn(gp.getPlayer());
        }
        broadcast("§aGame started! Fight!");
    }

    @Override
    protected void onGameEnd(List<UUID> winners) {
        // Called when end(winners) is invoked.
        // Players are still in the world here — use this for final messages,
        // fireworks, etc. They are returned to lobby automatically after 3s.
    }

    @Override
    protected void onPlayerJoin(GamePlayerImpl player) {
        // Called when a player is added to this game instance.
        // Inventory is already cleared and state saved by the framework.
    }

    @Override
    protected void onPlayerLeave(GamePlayerImpl player) {
        // Called when a player leaves mid-game (disconnect, /leave, etc).
        // Check win conditions here if needed.
        checkWinCondition();
    }

    private void checkWinCondition() {
        List<GamePlayer> alive = getPlayers().stream()
            .filter(GamePlayer::isAlive)
            .toList();

        if (alive.size() == 1) {
            end(List.of(alive.get(0).getUniqueId()));
        } else if (alive.isEmpty()) {
            end(); // draw
        }
    }
}
```

### What the framework does automatically

When a player joins a game instance:
- Their inventory and game mode are saved
- Their inventory is cleared and effects removed
- They are teleported to the world spawn (you can override this in `onGameStart`)
- They are assigned to a team if teams are configured

When a game ends:
- Players are returned to adventure mode with full health/hunger
- Inventories are cleared and the lobby selector item is given
- Players are teleported to the lobby spawn
- The game world is deleted
- The replay recording is saved

You do not need to handle any of this yourself.

---

## Teams

Enable teams in your `Minigame` constructor or via the admin command. Team names must match a `ChatColor` name (e.g. `"red"`, `"blue"`, `"green"`).

```java
public SkyWars(JavaPlugin plugin) {
    super("skywars", "&bSky&fWars", 4, 12, plugin, SkyWarsGame.class);
    setSupportsTeams(true);
    addTeam("red");
    addTeam("blue");
}
```

Players are auto-assigned to the team with the fewest members when they join. In your game instance:

```java
// Get a player's team
getPlayerTeam(player.getUniqueId()).ifPresent(team -> {
    team.addScore(1);
    broadcast(team.getColor() + team.getName() + " §7scored a point!");
});

// Check if a team is eliminated
getTeam("red").ifPresent(team -> {
    if (team.getMembers().stream().noneMatch(GamePlayer::isAlive)) {
        team.setEliminated(true);
    }
});

// Teleport a player to their team spawn
teleportToTeamSpawn(player, "red");
```

Team spawn points are set in-game via:
```
/kokopixel addspawn skywars red
```

---

## Statistics

Stats are per-player, per-game-instance integers. They persist for the lifetime of the game and are accessible via the API after the game ends (until the instance is cleaned up).

```java
// In your game instance
incrementStat(player.getUniqueId(), "kills", 1);
setStat(player.getUniqueId(), "deaths", 0);
int kills = getStat(player.getUniqueId(), "kills");

// Via GamePlayer (also updates the instance stats)
gp.addKill();
gp.addDeath();
gp.incrementStat("blocks_broken");
gp.setStat("score", 100);

// From outside via the API
KokoPixelAPI.get().getPlayerStat(playerId, gameId, "kills");
```

---

## Scheduled Tasks

Use `runTaskLater` and `runTaskTimer` instead of the Bukkit scheduler directly. These tasks are automatically cancelled when the game ends, and they check the game state before running so you don't get callbacks on a finished game.

```java
// Run once after 5 seconds
runTaskLater(() -> {
    broadcast("§cBorder shrinking in 5 seconds!");
}, 100L);

// Run every second
runTaskTimer(() -> {
    getPlayers().forEach(gp -> {
        gp.getPlayer().setLevel(timeRemaining--);
    });
}, 0L, 20L);
```

---

## Game Lifecycle

```
WAITING → COUNTDOWN → STARTING → ACTIVE → ENDING → ENDED
```

| State | Description |
|---|---|
| `WAITING` | Game created, not yet started |
| `COUNTDOWN` | 5-second countdown before start (public games only) |
| `STARTING` | `onGameStart()` is being called |
| `ACTIVE` | Game is running — your main gameplay phase |
| `ENDING` | `onGameEnd()` called, 3-second delay before cleanup |
| `ENDED` | World deleted, players returned to lobby |

Check the state in your listeners:

```java
@EventHandler
public void onPlayerDeath(PlayerDeathEvent e) {
    KokoPixelAPI api = KokoPixelAPI.get();
    api.getGame(e.getEntity()).ifPresent(game -> {
        if (game.getState() != GameState.ACTIVE) return;
        // handle death
    });
}
```

To end the game from your code:

```java
// With winners
end(List.of(winnerUUID));

// Draw / no winners
end();
```

---

## Bukkit Events

KokoPixel fires these events on the Bukkit event bus. Listen to them from any plugin.

### `GameStartEvent`
Fired when a game transitions to `ACTIVE`.

```java
@EventHandler
public void onGameStart(GameStartEvent e) {
    GameInstance game = e.getGame();
    List<UUID> players = e.getPlayers();
}
```

### `GameEndEvent`
Fired when `end()` is called.

```java
@EventHandler
public void onGameEnd(GameEndEvent e) {
    GameInstance game = e.getGame();
    List<UUID> winners = e.getWinners(); // empty list = draw
}
```

### `PlayerJoinGameEvent`
Fired when a player is added to a game instance.

```java
@EventHandler
public void onPlayerJoinGame(PlayerJoinGameEvent e) {
    Player player = e.getPlayer();
    GameInstance game = e.getGame();
}
```

### `PlayerLeaveGameEvent`
Fired when a player leaves a game (mid-game disconnect or forced removal).

```java
@EventHandler
public void onPlayerLeaveGame(PlayerLeaveGameEvent e) {
    Player player = e.getPlayer();
    GameInstance game = e.getGame();
}
```

---

## KokoPixelAPI Reference

Access via `KokoPixelAPI.get()`.

| Method | Description |
|---|---|
| `getGame(Player)` | Get the game a player is currently in |
| `getGame(UUID)` | Get the game by player UUID |
| `isInGame(Player)` | Whether a player is in an active game |
| `joinQueue(Player, String)` | Add a player to the queue for a game type |
| `leaveQueue(Player)` | Remove a player from their queue |
| `isInQueue(Player)` | Whether a player is queued |
| `enableSpectator(Player)` | Put a player into spectator mode |
| `enableSpectator(Player, boolean)` | Spectator mode with optional lobby return item |
| `disableSpectator(Player)` | Remove spectator mode and restore state |
| `isSpectating(Player)` | Whether a player is spectating |
| `teleportToPlayer(Player, Player)` | Teleport a spectator to a target |
| `getCurrentServerId()` | This server's configured ID |
| `setGameSelectorBlock(Location, String)` | Register a block as a game selector |
| `removeGameSelectorBlock(Location)` | Unregister a game selector block |
| `sendGameMessage(UUID, String)` | Broadcast a message to all players in a game |
| `getPlayerStat(UUID, UUID, String)` | Get a stat value for a player in a specific game |

---

## GameInstance Reference

Available inside `GameInstanceImpl` via `this`, or from outside via `KokoPixelAPI.get().getGame(player)`.

| Method | Description |
|---|---|
| `getGameId()` | Unique UUID for this game instance |
| `getGameType()` | The minigame name (e.g. `"skywars"`) |
| `getState()` | Current `GameState` |
| `getWorld()` | The cloned game world |
| `getPlayers()` | All players currently in the game |
| `getPlayer(UUID)` | Get a specific `GamePlayer` |
| `getTeams()` | All teams |
| `getTeam(String)` | Get a team by name |
| `getPlayerTeam(UUID)` | Get the team a player is on |
| `broadcast(String)` | Send a prefixed message to all players |
| `broadcastTitle(String, String, int, int, int)` | Show a title to all players |
| `setStat(UUID, String, int)` | Set a stat value |
| `incrementStat(UUID, String, int)` | Add to a stat value |
| `getStat(UUID, String)` | Read a stat value |
| `getStats(UUID)` | Get all stats for a player as a map |
| `teleport(Player, Location)` | Teleport a player |
| `teleportToSpawn(Player)` | Teleport to a random spawn point |
| `teleportToTeamSpawn(Player, String)` | Teleport to a team spawn point |
| `end(List<UUID>)` | End the game with winners |
| `end()` | End the game with no winners (draw) |
| `isPrivate()` | Whether this is a private (party) game |
| `runTaskLater(Runnable, long)` | Schedule a task, auto-cancelled on game end |
| `runTaskTimer(Runnable, long, long)` | Repeating task, auto-cancelled on game end |
| `getData()` | Arbitrary key-value store for your game data |

---

## GamePlayer Reference

Wraps a `Player` with game-specific state.

| Method | Description |
|---|---|
| `getPlayer()` | The underlying Bukkit `Player` |
| `getUniqueId()` | Player UUID |
| `getName()` | Player name |
| `getTeam()` | The player's team, if any |
| `isAlive()` | Whether the player is alive in-game |
| `setAlive(boolean)` | Mark alive/dead — setting false puts them in `SPECTATOR` gamemode |
| `addKill()` | Increment kill count and `kills` stat |
| `addDeath()` | Increment death count and `deaths` stat |
| `getKills()` / `getDeaths()` | Kill/death counts |
| `getStat(String)` | Read a custom stat |
| `setStat(String, int)` | Set a custom stat |
| `incrementStat(String)` | Increment a custom stat by 1 |
| `giveItems(ItemStack...)` | Clear inventory and give items |
| `addEffects(PotionEffect...)` | Apply potion effects |
| `clearEffects()` | Remove all potion effects |
| `saveInventory()` | Save current inventory (called automatically on join) |
| `resetInventory()` | Restore saved inventory |

---

## GameTeam Reference

| Method | Description |
|---|---|
| `getName()` | Team name |
| `getColor()` | `ChatColor` derived from the team name |
| `getMembers()` | All `GamePlayer` members |
| `addMember(GamePlayer)` | Add a member |
| `removeMember(GamePlayer)` | Remove a member |
| `isMember(UUID)` | Check membership |
| `getMemberCount()` | Number of members |
| `getScore()` / `setScore(int)` / `addScore(int)` | Team score |
| `isEliminated()` / `setEliminated(boolean)` | Elimination state |
| `getSpawnPoint()` / `setSpawnPoint(Location)` | Team spawn |

---

## GameState Reference

| State | When |
|---|---|
| `WAITING` | Game object created, not started |
| `COUNTDOWN` | Public game has enough players, counting down |
| `STARTING` | `onGameStart()` executing |
| `ACTIVE` | Game is live |
| `ENDING` | `onGameEnd()` executed, cleanup pending |
| `ENDED` | Fully cleaned up |

---

## Full Example

A minimal last-player-standing deathmatch:

```java
// Deathmatch.java
public class Deathmatch extends Minigame {
    public Deathmatch(JavaPlugin plugin) {
        super("deathmatch", "&cDeathmatch", 2, 8, plugin, DeathmatchGame.class);
    }
}

// DeathmatchGame.java
public class DeathmatchGame extends GameInstanceImpl implements Listener {

    public DeathmatchGame(Minigame minigame, World world, JavaPlugin plugin) {
        super(minigame, world, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    protected void onGameStart() {
        for (GamePlayer gp : getPlayers()) {
            gp.giveItems(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.COOKED_BEEF, 8)
            );
            teleportToSpawn(gp.getPlayer());
        }
        broadcast("§cLast one standing wins!");
    }

    @Override
    protected void onGameEnd(List<UUID> winners) {
        if (!winners.isEmpty()) {
            getPlayer(winners.get(0)).ifPresent(w ->
                broadcastTitle("§6" + w.getName(), "§eWins!", 10, 60, 20));
        }
    }

    @Override
    protected void onPlayerJoin(GamePlayerImpl player) {}

    @Override
    protected void onPlayerLeave(GamePlayerImpl player) {
        checkWinCondition();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (getState() != GameState.ACTIVE) return;
        getPlayer(e.getEntity().getUniqueId()).ifPresent(gp -> {
            gp.addDeath();
            gp.setAlive(false);
            broadcast("§c" + gp.getName() + " §7was eliminated!");
            checkWinCondition();
        });
    }

    private void checkWinCondition() {
        List<GamePlayer> alive = getPlayers().stream()
            .filter(GamePlayer::isAlive).toList();
        if (alive.size() == 1) end(List.of(alive.get(0).getUniqueId()));
        else if (alive.isEmpty()) end();
    }
}

// YourPlugin.java
public class YourPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () ->
            KokoPixel.getInstance().getMinigameManager()
                .registerMinigame(new Deathmatch(this)), 1L);
    }
}
```

After registering, set up the template world and spawn points in-game:

```
/kokopixel setworld deathmatch
/kokopixel addspawn deathmatch
/kokopixel addspawn deathmatch
```

Players can then queue via `/minigame join deathmatch` or the game selector GUI.
