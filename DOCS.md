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
- [Bot API](#bot-api)
  - [Creating a Bot Engine](#creating-a-bot-engine)
  - [Creating a Bot Controller](#creating-a-bot-controller)
  - [Spawning a Bot into a Game](#spawning-a-bot-into-a-game)
  - [BotHandle Reference](#bothandle-reference)
  - [BotSenses Reference](#botsenses-reference)
  - [BotActions Reference](#botactions-reference)
  - [BotManager Reference](#botmanager-reference)

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

---

## Death Handling

By default KokoPixel handles player death by putting them in spectator mode and teleporting them to the lobby when `setAlive(false)` is called or when `removePlayer` fires.

If your minigame needs custom death logic (respawning, in-world spectating, elimination checks), call `setHandlesDeath(true)` in your `Minigame` constructor:

```java
public BedWarsMinigame(JavaPlugin plugin) {
    super("bedwars", "&cBedWars", 2, 4, plugin, BedWarsGame.class);
    setHandlesDeath(true); // we manage respawn and elimination ourselves
}
```

When `handlesDeath()` returns `true`:
- `GamePlayerImpl.setAlive(false)` does NOT set `GameMode.SPECTATOR`
- `GameInstanceImpl.removePlayer` does NOT teleport the player to lobby or clear their inventory

Your `onPlayerLeave` and `onGameEnd` callbacks are still fired normally. You are responsible for teleporting eliminated players and cleaning up their state.

---

## Bot API

KokoPixel includes a packet-based bot system that lets you add AI-controlled fake players to any game. Bots are rendered client-side only — they use the same NMS packet technique as the replay system, so they look like real players to everyone in the game world with no real client connected.

### How it works

- Bots are `ServerPlayer` NMS entities with a bare `GameProfile` (no skin data). Minecraft assigns a default skin client-side.
- You define a `BotEngine` that acts as a factory, and a `BotController` that holds the AI logic for one bot in one game.
- The `BotHandle` is the bot's "body" — low-level movement and equipment packets.
- `BotHandle.getSenses()` gives the bot its eyes — FOV checks, block scanning, enemy detection, team awareness.
- `BotHandle.getActions()` gives the bot higher-level actions — pathfinding, attacking, block-breaking, bridging.

---

### Setup

No extra dependency is needed — the bot API ships with KokoPixel.

---

### Creating a Bot Engine

Extend `cc.kokodev.kokopixel.bots.BotEngineImpl`:

```java
public class MyBotEngine extends BotEngineImpl {

    public MyBotEngine(JavaPlugin plugin) {
        super("mygame_basic", "&7Basic Bot", plugin);
    }

    @Override
    public BotController createController(BotHandle bot, GameInstance game) {
        return new MyBotController();
    }

    @Override
    public int getTickInterval() { return 2; } // run AI every 2 ticks — less CPU cost
}
```

Register it in `onEnable` (same 1-tick delay pattern as minigames):

```java
@Override
public void onEnable() {
    Bukkit.getScheduler().runTaskLater(this, () ->
        KokoPixel.getInstance().getBotManager().registerEngine(new MyBotEngine(this)), 1L);
}
```

---

### Creating a Bot Controller

Implement `cc.kokodev.kokopixel.api.bot.BotController`:

```java
public class MyBotController implements BotController {

    @Override
    public void onStart(BotHandle bot, GameInstance game) {
        // Called once after the bot spawns. Give it equipment here.
        bot.setMainHand(new ItemStack(Material.IRON_SWORD));
        bot.setHelmet(new ItemStack(Material.IRON_HELMET));
        bot.getActions().setInventory(List.of(
            new ItemStack(Material.IRON_PICKAXE),
            new ItemStack(Material.IRON_SWORD)
        ));
    }

    @Override
    public void onTick(BotHandle bot, GameInstance game) {
        BotSenses s = bot.getSenses();
        BotActions a = bot.getActions();

        // Find nearest enemy and chase/attack them
        s.getNearestEnemy(16, game).ifPresent(enemy -> {
            Location target = enemy.getPlayer().getLocation();
            a.pathfindTo(target, 0.25);
            a.lookAt(enemy.getPlayer().getEyeLocation(), 10f);

            if (s.distanceTo(target) < 3.0 && s.hasLineOfSight(enemy.getPlayer())) {
                a.attack(enemy.getPlayer());
            }
        });

        // Bridge over gaps while moving
        if (s.isAtEdge()) {
            a.bridgeStep(Material.OAK_PLANKS);
        }
    }

    @Override
    public boolean onDeath(BotHandle bot, GameInstance game) {
        // Return true to keep the bot alive (you handle respawn)
        // Return false to remove the bot
        bot.teleport(game.getWorld().getSpawnLocation());
        return true;
    }

    @Override
    public void onStop(BotHandle bot, GameInstance game) {
        // Clean up any state when the bot is removed or the game ends
    }
}
```

---

### Spawning a Bot into a Game

Call `spawnBot` from within your `GameInstanceImpl.onGameStart()` or from a command:

```java
BotHandle bot = KokoPixel.getInstance().getBotManager()
        .spawnBot("mygame_basic", "Bot_1", spawnLocation, game);
```

You can also pass a `BotEngine` instance directly:

```java
BotHandle bot = KokoPixel.getInstance().getBotManager()
        .spawnBot(new MyBotEngine(plugin), "Bot_1", spawnLocation, game);
```

Bots are automatically despawned when the game ends — you do not need to clean them up manually.

---

### Removing a Bot mid-game

```java
KokoPixel.getInstance().getBotManager().removeBot(bot.getUniqueId(), game);
```

### Notifying a bot of death (from your death listener)

```java
KokoPixel.getInstance().getBotManager().notifyBotDeath(deadPlayerUUID, game);
```

`BotController.onDeath` will be called. Return `true` to keep it alive, `false` to remove it.

---

### BotHandle Reference

The bot's "body". Passed into every `BotController` callback.

| Method | Description |
|---|---|
| `getUniqueId()` | The bot's UUID |
| `getName()` | Display name shown above its head |
| `getLocation()` | Current position in the world |
| `getWorld()` | The world the bot lives in |
| `teleport(Location)` | Instantly move the bot (broadcasts teleport packet) |
| `move(dx, dy, dz, yaw, pitch)` | Move by a relative delta (uses relative movement packet) |
| `setHeadRotation(yaw, pitch)` | Update head rotation only |
| `setSneaking(boolean)` | Set sneak state and broadcast metadata packet |
| `setSprinting(boolean)` | Set sprint state and broadcast metadata packet |
| `swingMainHand()` | Play the arm-swing animation |
| `setHelmet(ItemStack)` | Set head armour slot |
| `setChestplate(ItemStack)` | Set chest armour slot |
| `setLeggings(ItemStack)` | Set legs armour slot |
| `setBoots(ItemStack)` | Set feet armour slot |
| `setMainHand(ItemStack)` | Set held item |
| `setOffHand(ItemStack)` | Set off-hand item |
| `getSenses()` | Returns the bot's `BotSenses` perception layer |
| `getActions()` | Returns the bot's `BotActions` higher-level action layer |

---

### BotSenses Reference

Read-only world perception. Access via `bot.getSenses()`.

**Vision**

| Method | Description |
|---|---|
| `canSeeLocation(Location, double)` | True if location is within 70° FOV and max distance |
| `canSeeLocation(Location, double, float)` | Same with custom FOV half-angle in degrees |
| `hasLineOfSight(Block)` | Unobstructed line-of-sight to block centre |
| `hasLineOfSight(Player)` | Unobstructed line-of-sight to a player's eye |

**Block scanning**

| Method | Description |
|---|---|
| `getNearbyBlocks(double, Material...)` | All non-air blocks within radius, optionally filtered |
| `findNearestBlock(double, Material...)` | Nearest block of given material(s) within radius |
| `getTargetBlock(double)` | Block the bot is looking at (ray-cast) |
| `getBlockBelow()` | Block directly under the bot's feet |
| `isOnGround()` | True if the bot is standing on a solid surface |
| `isAtEdge()` | True if walking forward would step off a ledge |
| `getBlockAtHead()` | Block at head height (Y+1) |

**Player / entity perception**

| Method | Description |
|---|---|
| `getNearbyPlayers(double)` | All real players within radius |
| `getNearestPlayer(double)` | Nearest player within radius |
| `getNearestEnemy(double, GameInstance)` | Nearest living enemy player |
| `getNearestAlly(double, GameInstance)` | Nearest living ally player |
| `getVisibleEnemies(GameInstance)` | All enemies with line-of-sight |

**Team awareness**

| Method | Description |
|---|---|
| `getAliveTeams(GameInstance)` | All non-eliminated teams |
| `getOwnTeam(GameInstance)` | The bot's own team, if assigned |
| `isAlly(Player, GameInstance)` | True if player is on the same team |
| `isEnemy(GamePlayer, GameInstance)` | True if player is on an enemy team |

**Geometry**

| Method | Description |
|---|---|
| `distanceTo(Location)` | 3D distance from bot to location |
| `horizontalDistanceTo(Location)` | Horizontal-only distance (ignores Y) |
| `yawToward(Location)` | Yaw angle needed to face a location |
| `pitchToward(Location)` | Pitch angle needed to face a location |

---

### BotActions Reference

Stateful higher-level actions. Access via `bot.getActions()`. Call from `onTick` each tick — multi-tick actions (pathfinding, block-breaking) progress automatically.

**Movement**

| Method | Description |
|---|---|
| `walkToward(Location, double)` | Step toward location at speed blocks/tick. Returns true when arrived |
| `pathfindTo(Location, double)` | A* pathfind toward location, auto-recalculates when stale. Returns true when arrived |
| `pathfindTo(Location, double, int)` | Same with custom max A* node expansion per call |
| `jump()` | Jump if on the ground |
| `setSprinting(boolean)` | Set sprint state |
| `setSneaking(boolean)` | Set sneak state |

**Combat**

| Method | Description |
|---|---|
| `attack(Player)` | Look at target and play attack animation |
| `swingMainHand()` | Play arm-swing animation only |

**Block interaction**

| Method | Description |
|---|---|
| `breakBlock(Block)` | Advance break progress by one tick. Returns true when block is broken |
| `placeBlock(Block, BlockFace, Material)` | Place a block against the given face. Returns false if out of reach or occupied |
| `bridgeStep(Material)` | Place one block under the bot's trailing edge while moving forward |

**Look**

| Method | Description |
|---|---|
| `lookAt(Location, float)` | Smoothly rotate toward location by at most N degrees/tick. Returns true when fully aimed |
| `lookAtInstant(Location)` | Snap to face location immediately |

**Inventory / tool**

| Method | Description |
|---|---|
| `setInventory(List<ItemStack>)` | Set the bot's virtual inventory for tool-speed calculations |
| `bestToolFor(Material)` | Returns the best tool in the virtual inventory for breaking a given block |

**State queries**

| Method | Description |
|---|---|
| `getCurrentPath()` | The active A* path waypoints |
| `clearPath()` | Force path recalculation on next `pathfindTo` call |
| `isJumping()` | True if the bot is airborne from a jump |
| `isSprinting()` | True if the bot is sprinting |
| `isSneaking()` | True if the bot is sneaking |

---

### BotManager Reference

Access via `KokoPixel.getInstance().getBotManager()` or `KokoPixelAPI.get().getBotManager()`.

| Method | Description |
|---|---|
| `registerEngine(BotEngine)` | Register a bot engine so it can be used by name |
| `unregisterEngine(String)` | Remove a registered engine |
| `getEngine(String)` | Get a registered engine by id |
| `getEngines()` | All registered engines |
| `spawnBot(String, String, Location, GameInstance)` | Spawn a bot using an engine id |
| `spawnBot(BotEngine, String, Location, GameInstance)` | Spawn a bot using an engine instance |
| `removeBot(UUID, GameInstance)` | Remove a specific bot |
| `removeAllBots(GameInstance)` | Remove all bots in a game (called automatically on game end) |
| `notifyBotDeath(UUID, GameInstance)` | Signal that a bot died; delegates to `BotController.onDeath` |
| `isBot(UUID)` | True if the UUID belongs to an active bot |
| `getBotsInGame(UUID)` | Set of bot UUIDs in a given game |
| `getHandle(UUID)` | Get the `BotHandle` for a bot UUID |
| `getController(UUID)` | Get the `BotController` for a bot UUID |
| `onPlayerJoinWorld(Player, UUID)` | Call when a player joins a game world so they receive spawn packets for existing bots |
