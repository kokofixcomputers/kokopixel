# KokoPixel

A Paper 1.21 minigames framework with BungeeCord support. Handles game lifecycle, queuing, parties, spectating, replays, and cross-server coordination so minigame plugins can focus on gameplay logic.

## Features

**Minigame Framework**
- Register minigames via the `KokoPixelAPI` — get world management, queuing, teams, and stats for free
- Dynamic world cloning per game instance, deleted on game end
- Team support with per-team spawn points
- Per-player and per-game statistics

**Queue System**
- Public and private (party) queues per game type
- Cross-server queue merging — every 60s servers compare queue sizes and consolidate smaller queues into the largest one via BungeeCord routing
- Party-aware queuing with size validation including remote party members

**Party System**
- Full cross-server party support — members can be on different servers
- BungeeCord proxy tracks party membership and routes remote members when the leader queues or starts a replay
- Clickable party invites with Adventure text components

**Spectator System**
- Spectator mode identical to dying in-game — invisible, flying, invulnerable
- Cycle through players, adjust flight speed, return to lobby or requeue
- Admin spectating via `/admingui`

**Replay System**
- Every game is recorded server-side: player positions, held items, pose, block breaks/places
- Recordings stored to disk, auto-deleted after 6 hours (timer survives server restarts)
- Playback uses NMS fake player packets — viewers see ghost players with correct skins and animations
- Cross-server replay index stored on the BungeeCord proxy — new servers receive the full index on connect, no desync
- Party leaders bring their whole party into a replay, including members on other servers
- `/replay` — browse and watch games you participated in

**Admin Panel** (`/admingui`)
- View and spectate active games
- Browse all replays across the entire network, watch local ones directly or route to the host server
- Force-stop all active games

**Social**
- `/msg` and `/r` — cross-server private messaging
- `/friend` — persistent friend list with cross-server online status and server location
- Friend join/leave notifications

**Ranks**
- Configurable rank system with colored prefixes in chat and tab list

## Requirements

- Paper 1.21.8
- BungeeCord (optional — enables cross-server features)
- Java 21

## Building

```bash
./gradlew build
```

JAR output: `build/libs/KokoPixel.jar`

## Setup

1. Drop `KokoPixel.jar` into your Paper server's `plugins/` folder
2. Start the server once to generate config, then stop it
3. Edit `plugins/KokoPixel/config.yml`:
   ```yaml
   server-id: "lobby-1"   # unique per server
   bungee:
     enabled: true         # if behind BungeeCord
   ```
4. Start the server, join, and run `/kokopixel setlobby` to set the spawn
5. Register your minigame plugin, then:
   ```
   /kokopixel setworld <game>       # stand in the template world
   /kokopixel addspawn <game>       # stand at each spawn point
   ```
6. Duplicate the server folder for each additional node, changing `server-id` each time

## Commands

| Command | Permission | Description |
|---|---|---|
| `/minigame [join\|leave\|list]` | `kokopixel.minigame` | Queue for games |
| `/party <create\|invite\|accept\|...>` | `kokopixel.party` | Party management |
| `/lobby` | `kokopixel.lobby` | Return to lobby |
| `/replay` | `kokopixel.replay` | Watch past game recordings |
| `/msg <player> <message>` | `kokopixel.msg` | Cross-server private message |
| `/r <message>` | `kokopixel.msg` | Reply to last message |
| `/friend <add\|list\|accept\|...>` | `kokopixel.friend` | Friends list |
| `/kokopixel` | `kokopixel.admin` | Admin setup commands |
| `/admingui` | `kokopixel.admin` | Admin panel GUI |

## API

Other plugins can register minigames and interact with the framework:

```java
// Register a minigame
KokoPixelAPI api = KokoPixelAPI.get();

// Check if a player is in a game
api.isInGame(player);

// Get the game a player is in
api.getGame(player).ifPresent(game -> { ... });

// Queue a player
api.joinQueue(player, "yourgamename");

// Spectator control
api.enableSpectator(player);
api.disableSpectator(player);
```

Minigame plugins extend `Minigame` and `GameInstanceImpl` to define their game logic via `onGameStart`, `onGameEnd`, `onPlayerJoin`, and `onPlayerLeave` hooks.

## Project Structure

```
src/main/java/cc/kokodev/kokopixel/
├── api/            KokoPixelAPI interface and provider
├── bungee/         BungeeCord proxy plugin + Paper-side listener
├── commands/       All player and admin commands
├── friends/        Friend list persistence and cross-server status
├── listeners/      Bukkit event listeners
├── menu/           Chest GUI menus (game selector, admin panel)
├── minigames/      Game instance, player, team implementations
├── party/          Party system including cross-server state
├── queue/          Queue management and cross-server merging
├── ranks/          Rank system
├── replay/         Recording, playback, session management
├── spectator/      Spectator mode
├── util/           Shared message formatting (Msg.java)
└── world/          World cloning and cleanup
```
