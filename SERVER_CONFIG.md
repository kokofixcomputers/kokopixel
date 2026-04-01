# KokoPixel — Server Owner Configuration Guide

Everything a server owner needs to configure, manage, and maintain KokoPixel.

## Table of Contents

- [File Overview](#file-overview)
- [config.yml](#configyml)
- [Lobby Spawn](#lobby-spawn)
- [Ranks](#ranks)
- [Minigame Setup](#minigame-setup)
- [Game Selector](#game-selector)
- [Proxy Setup](#proxy-setup)
  - [BungeeCord](#bungeecord)
  - [Velocity](#velocity)
- [Permissions](#permissions)
- [Commands Reference](#commands-reference)
- [Data Files](#data-files)
- [Multi-Server Setup](#multi-server-setup)

---

## File Overview

After first start, KokoPixel creates the following structure inside `plugins/KokoPixel/`:

```
plugins/KokoPixel/
├── config.yml          Main configuration
├── lobby.yml           Lobby spawn location (written by /kokopixel setlobby)
├── ranks/              One .yml file per rank
│   ├── owner.yml
│   ├── admin.yml
│   ├── mod.yml
│   ├── helper.yml
│   ├── builder.yml
│   ├── vip.yml
│   ├── mvp.yml
│   └── default.yml
├── playerdata/         One .yml per player storing their rank
├── friends/            One .yml per player storing their friend list
├── minigames/          One .yml per registered minigame storing spawn points
└── replays/            Binary .replay files, auto-deleted after 6 hours
```

---

## config.yml

Full annotated config with every available option:

```yaml
# ─────────────────────────────────────────────────────────────────────────────
# Server Identity
# ─────────────────────────────────────────────────────────────────────────────

# Unique identifier for this server on the network.
# Used in cross-server messaging, replay index, and party routing.
# Auto-generated as a random 8-character string if left empty.
# Must be unique across all servers in your network.
server-id: ""

# ─────────────────────────────────────────────────────────────────────────────
# Proxy Mode
# ─────────────────────────────────────────────────────────────────────────────

# Set to true to enable cross-server features (queue merging, cross-server
# parties, replay routing, friend online status, /msg cross-server).
# Requires a proxy plugin (BungeeCord or Velocity) with KokoPixel installed.
bungee:
  enabled: false

# Which proxy software you are using.
# Options: "bungeecord" or "velocity"
# When using Velocity, ensure bungee-plugin-message-channel is set to true
# in your velocity.toml (it is true by default).
proxy:
  type: "bungeecord"

# ─────────────────────────────────────────────────────────────────────────────
# Lobby
# ─────────────────────────────────────────────────────────────────────────────

# The world name that is treated as the lobby.
# Players in this world cannot break blocks, drop items, take damage, or lose hunger.
# Chat in this world is scoped to lobby players only.
# Set the spawn point with /kokopixel setlobby (writes to lobby.yml).
lobby:
  world: "world"

# ─────────────────────────────────────────────────────────────────────────────
# Minigame Defaults
# ─────────────────────────────────────────────────────────────────────────────

minigames:
  # Fallback min/max player counts used if a minigame plugin does not specify them.
  default-min-players: 2
  default-max-players: 16

  worlds:
    # Whether to delete the cloned game world when the game ends.
    # Set to false only for debugging — worlds accumulate fast.
    delete-on-end: true

    # Default Bukkit world environment for cloned game worlds.
    # Options: NORMAL, NETHER, THE_END
    default-environment: NORMAL

# ─────────────────────────────────────────────────────────────────────────────
# Game Selector Item
# ─────────────────────────────────────────────────────────────────────────────

game-selector:
  # Whether to give the game selector compass to players on join.
  enabled: true

  # The item material. Must be a valid Bukkit Material name.
  material: COMPASS

  # Display name of the item. Supports & colour codes.
  name: "§a§lGame Selector §7(Right Click)"

  # Hotbar slot (0–8) the item is placed in.
  slot: 0

  # Lore lines shown on the item. Supports & colour codes.
  lore:
    - "§7Right-click to open"
    - "§7the game selector menu"

# ─────────────────────────────────────────────────────────────────────────────
# Game Selector Blocks (auto-managed, do not edit manually)
# ─────────────────────────────────────────────────────────────────────────────

# Populated automatically by /kokopixel setselector <game>.
# Each entry maps a block location to a game type.
# Clicking the block opens the game selector filtered to that game.
selector-blocks: {}
```

---

## Lobby Spawn

The lobby spawn is stored separately in `lobby.yml` and is written by the in-game command — do not edit it manually unless you know the exact coordinates.

**Setting the spawn:**
1. Stand exactly where you want players to spawn (facing direction matters)
2. Run `/kokopixel setlobby`

The file it writes looks like:
```yaml
world: world
x: 0.5
y: 64.0
z: 0.5
yaw: 0.0
pitch: 0.0
```

If `lobby.yml` does not exist or the world is not loaded, KokoPixel falls back to the default world's spawn point.

---

## Ranks

Ranks are stored as individual YAML files in `plugins/KokoPixel/ranks/`. Default ranks are created on first start. You can edit them freely — changes take effect on next server start or plugin reload.

**Default ranks (highest to lowest priority):**

| Rank | Prefix | Priority |
|---|---|---|
| owner | `[OWNER]` red | 100 |
| admin | `[ADMIN]` red | 90 |
| mod | `[MOD]` blue | 80 |
| helper | `[HELPER]` green | 70 |
| builder | `[BUILDER]` dark green | 60 |
| vip | `[VIP]` gold | 50 |
| mvp | `[MVP]` aqua | 40 |
| default | (no prefix) gray | 0 |

**Rank file format** (`plugins/KokoPixel/ranks/vip.yml`):

```yaml
rank:
  name: vip              # Internal ID — used in commands
  prefix: "&6[VIP]"      # Prefix shown in chat and tab list. Supports & codes.
  displayName: "VIP"     # Human-readable name
  color: GOLD            # ChatColor name — used for player name colour in chat
  priority: 50           # Higher = shown first in tab list. Must be unique.
  default: false         # Only one rank should have default: true (the fallback rank)
```

**Adding a custom rank:**
1. Create `plugins/KokoPixel/ranks/myrank.yml` with the format above
2. Restart the server (or reload KokoPixel)

**Assigning a rank to a player:**

There is no built-in command for this yet — assign ranks programmatically via the `RankManager` API, or edit the player's data file directly:

`plugins/KokoPixel/playerdata/<player-uuid>.yml`:
```yaml
rank: vip
```

---

## Minigame Setup

Each registered minigame stores its configuration in `plugins/KokoPixel/minigames/<name>.yml`. This file is written automatically — you should not need to edit it manually. It stores the template world name and all spawn points.

**Setting up a minigame in-game:**

```
# 1. Load the world you want to use as the template
#    (the world that gets cloned for each game)

# 2. Stand in that world and run:
/kokopixel setworld <gamename>

# 3. Stand at each spawn point and run:
/kokopixel addspawn <gamename>

# 4. For team games, add team-specific spawns:
/kokopixel addspawn <gamename> red
/kokopixel addspawn <gamename> blue

# 5. To add teams to a game:
/kokopixel addteam <gamename> red
/kokopixel addteam <gamename> blue

# 6. To clear all spawn points and start over:
/kokopixel clearspawns <gamename>
/kokopixel clearspawns <gamename> red
```

**The minigame config file** (`plugins/KokoPixel/minigames/skywars.yml`):

```yaml
name: skywars
display-name: "&bSky&fWars"
min-players: 2
max-players: 12
template-world: skywars_template   # The world that gets cloned
supports-teams: false
teams: []
spawn-points:
  - world: skywars_template
    x: 100.5
    y: 80.0
    z: 100.5
    yaw: 0.0
    pitch: 0.0
  # ... more spawn points
team-spawn-points: {}
```

---

## Game Selector

Players receive a compass on join that opens the game selector GUI. You can also place physical blocks in the lobby that open the selector when right-clicked.

**Registering a selector block:**
1. Look at the block you want to use
2. Run `/kokopixel setselector <gamename>`

**Removing a selector block:**
1. Look at the block
2. Run `/kokopixel removeselector`

Selector block locations are saved to `config.yml` under `selector-blocks` automatically.

---

## Proxy Setup

### BungeeCord

**Paper server (`plugins/KokoPixel/config.yml`):**
```yaml
server-id: "lobby-1"   # unique per server
bungee:
  enabled: true
proxy:
  type: "bungeecord"
```

**BungeeCord proxy:**
Drop `KokoPixel.jar` into the BungeeCord `plugins/` folder. No extra configuration needed — it auto-registers on startup.

**BungeeCord `config.yml`:**
```yaml
ip_forward: true   # required
```

**Paper `spigot.yml`:**
```yaml
settings:
  bungeecord: true
```

---

### Velocity

**Paper server (`plugins/KokoPixel/config.yml`):**
```yaml
server-id: "lobby-1"
bungee:
  enabled: true
proxy:
  type: "velocity"
```

**Velocity proxy:**
Drop `KokoPixel.jar` into the Velocity `plugins/` folder. The same jar contains both the Paper plugin and the Velocity plugin.

**Velocity `velocity.toml`:**
```toml
# Required for the BungeeCord-compatible Connect message to work
bungee-plugin-message-channel = true

# Required for Paper servers to accept Velocity forwarding
player-info-forwarding-mode = "MODERN"
```

**Paper `config/paper-global.yml`:**
```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "your-velocity-secret"
```

---

## Permissions

All permissions default to `true` for regular players except `kokopixel.admin` which defaults to `op`.

| Permission | Default | Description |
|---|---|---|
| `kokopixel.*` | op | Grants all permissions |
| `kokopixel.minigame` | true | Use `/minigame` commands and queue for games |
| `kokopixel.party` | true | Use `/party` commands |
| `kokopixel.queue` | true | Join queues |
| `kokopixel.lobby` | true | Use `/lobby` to return to spawn |
| `kokopixel.replay` | true | Use `/replay` to watch past games |
| `kokopixel.friend` | true | Use `/friend` commands |
| `kokopixel.msg` | true | Use `/msg` and `/r` |
| `kokopixel.admin` | op | Use `/kokopixel` and `/admingui` |

To restrict a feature from regular players, negate the permission in your permissions plugin:

```yaml
# LuckPerms example — remove replay access from default group
/lp group default permission set kokopixel.replay false
```

---

## Commands Reference

### Player Commands

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/minigame` | `/mg` | `kokopixel.minigame` | Open game selector GUI |
| `/minigame join <game>` | | `kokopixel.minigame` | Join queue for a specific game |
| `/minigame leave` | | `kokopixel.minigame` | Leave current queue or game |
| `/minigame list` | | `kokopixel.minigame` | List all games and queue sizes |
| `/party create` | `/p create` | `kokopixel.party` | Create a party |
| `/party invite <player>` | | `kokopixel.party` | Invite a player |
| `/party accept <player>` | | `kokopixel.party` | Accept an invite |
| `/party leave` | | `kokopixel.party` | Leave your party |
| `/party kick <player>` | | `kokopixel.party` | Kick a member (leader only) |
| `/party disband` | | `kokopixel.party` | Disband the party (leader only) |
| `/party private` | | `kokopixel.party` | Make party private |
| `/party public` | | `kokopixel.party` | Make party public |
| `/party list` | | `kokopixel.party` | List party members |
| `/party transfer <player>` | | `kokopixel.party` | Transfer leadership |
| `/lobby` | `/hub`, `/spawn` | `kokopixel.lobby` | Return to lobby |
| `/replay` | | `kokopixel.replay` | Browse and watch past game recordings |
| `/friend add <player>` | `/f add` | `kokopixel.friend` | Send a friend request |
| `/friend accept <player>` | | `kokopixel.friend` | Accept a friend request |
| `/friend deny <player>` | | `kokopixel.friend` | Deny a friend request |
| `/friend remove <player>` | | `kokopixel.friend` | Remove a friend |
| `/friend list` | | `kokopixel.friend` | View friends and their online status |
| `/msg <player> <message>` | `/tell`, `/w` | `kokopixel.msg` | Send a private message (cross-server) |
| `/r <message>` | | `kokopixel.msg` | Reply to last private message |

### Admin Commands

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/kokopixel setlobby` | `/kp setlobby` | `kokopixel.admin` | Set lobby spawn to your position |
| `/kokopixel setworld <game>` | | `kokopixel.admin` | Set current world as template for a game |
| `/kokopixel addspawn <game> [team]` | | `kokopixel.admin` | Add spawn point at your position |
| `/kokopixel clearspawns <game> [team]` | | `kokopixel.admin` | Clear all spawn points |
| `/kokopixel addteam <game> <team>` | | `kokopixel.admin` | Add a team to a game |
| `/kokopixel setselector <game>` | | `kokopixel.admin` | Register looked-at block as game selector |
| `/kokopixel removeselector` | | `kokopixel.admin` | Remove looked-at game selector block |
| `/kokopixel servers` | | `kokopixel.admin` | Show this server's ID |
| `/kokopixel debug` | | `kokopixel.admin` | Show your current game/queue/spectator state |
| `/admingui` | | `kokopixel.admin` | Open the admin panel GUI |

**Admin GUI panels:**
- Active Games — view all running games, click to spectate
- All Replays — browse every recording across the network, click to watch or route to host server
- Stop All Games — force-end every active game

---

## Data Files

### `lobby.yml`
Written by `/kokopixel setlobby`. Do not edit manually unless necessary.

### `ranks/<name>.yml`
One file per rank. Edit freely, restart to apply. See [Ranks](#ranks) for format.

### `playerdata/<uuid>.yml`
Stores each player's assigned rank. Edit to manually change a player's rank:
```yaml
rank: vip
```

### `friends/<uuid>.yml`
Stores each player's friend list. Managed automatically.

### `minigames/<name>.yml`
Stores template world and spawn points for each registered minigame. Managed by `/kokopixel` commands.

### `replays/<uuid>.replay`
Binary recording files. Auto-deleted after 6 hours based on the timestamp inside the file — server restarts do not reset the timer. Do not edit these files.

---

## Multi-Server Setup

When running multiple servers behind a proxy:

1. Build the plugin once: `./gradlew build`
2. Copy `KokoPixel.jar` to every Paper server's `plugins/` folder
3. Copy `KokoPixel.jar` to the proxy's `plugins/` folder (BungeeCord or Velocity)
4. On each Paper server, set a unique `server-id` and enable `bungee.enabled: true`
5. Set `proxy.type` to match your proxy software

**What the proxy plugin stores (single source of truth):**
- Replay index — all servers push new recordings here; new servers receive the full index on connect
- Cross-server party membership — which server each member is on
- Network player list — used for `/msg` tab completion and friend online status

**What each Paper server stores locally:**
- Its own replay `.replay` files
- Player rank data
- Friend lists
- Minigame spawn point configs

**Duplicating a server:**
1. Stop the server
2. Copy the folder
3. In the copy, change `server-id` in `config.yml` to a new unique value
4. Register the new server in your proxy config
5. Start it up — it will automatically receive the full replay index from the proxy on first connect
