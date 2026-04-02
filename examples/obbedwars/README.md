# OBBedWars — KokoPixel Example Plugin

OneBlock BedWars. Each player has a bed. Mine your own bed for random loot. Enemies can destroy your bed. No bed + death = eliminated. Last player standing wins.

## Features

- 2–4 players, one per team (red/blue/yellow/lime by default)
- Mining your own bed gives random loot from a predefined table (wood, iron, swords, bows, obsidian, TNT, diamonds, ender pearls, etc.)
- Enemy breaking your bed destroys it permanently
- Die with bed → respawn at team spawn after 3 seconds
- Die without bed → spectator, eliminated
- Diamond generators spawn diamonds every 10 seconds
- Sidebar scoreboard shows all teams with ✔ (has bed), number (alive, no bed), or ✗ (eliminated)
- Full crafting — players can craft anything from what they collect

## Setup

1. Build KokoPixel first: `./gradlew build` in the root project
2. Build this plugin: `./gradlew build` in `examples/obbedwars/`
3. Drop both jars into your server's `plugins/` folder
4. Start the server
5. Load your map world, then run:

```
# Set the template world (the world KokoPixel will clone for each game)
/kokopixel setworld obbedwars

# Set team spawn points (stand at each spawn)
/obbedwars setup spawn red
/obbedwars setup spawn blue
/obbedwars setup spawn yellow
/obbedwars setup spawn lime

# Register beds (look at each bed)
/obbedwars setup bed red
/obbedwars setup bed blue
/obbedwars setup bed yellow
/obbedwars setup bed lime

# Add diamond generators (stand at each one)
/obbedwars setup diamondgen

# Save
/obbedwars setup save
```

6. Players queue via `/minigame join obbedwars` or the game selector GUI

## Customising Teams

Edit `config.yml` to change team names. Names must be valid Bukkit `ChatColor` names:
`red`, `blue`, `yellow`, `green`, `dark_green`, `aqua`, `gold`, `light_purple`, `white`

## Customising Loot

Edit the `LOOT_TABLE` list in `BedWarsGame.java`. Each entry is an array of `ItemStack`s dropped when a player mines their own bed.
