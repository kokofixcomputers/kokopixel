package cc.kokodev.kokopixel.world;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.Minigame;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class WorldManager {

    private final KokoPixel plugin;
    private final List<World> tempWorlds = new ArrayList<>();

    /** Files we never need to copy — session.lock is held exclusively by the JVM. */
    private static final Set<String> SKIP_FILES = Set.of("session.lock", "uid.dat");

    // Multiverse API class names — checked at runtime so we don't hard-depend on MV
    private static final String MV_CORE_CLASS = "com.onarandombox.MultiverseCore.MultiverseCore";
    private static final String MV_WORLD_MANAGER_CLASS = "com.onarandombox.MultiverseCore.api.MVWorldManager";

    public WorldManager(KokoPixel plugin) { this.plugin = plugin; }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates a game world by:
     *  1. Saving the template world to flush all dirty chunks to disk
     *  2. Temporarily unloading it (via Multiverse if present, plain Bukkit otherwise)
     *     so the JVM releases its file locks on the region files
     *  3. Copying the folder on an async thread
     *  4. Reloading the template world
     *  5. Loading the new copy and calling the callback on the main thread
     */
    public void createGameWorldAsync(Minigame mg, Consumer<World> callback) {
        World template = mg.getTemplateWorld();
        if (template == null) {
            plugin.getLogger().severe("[WorldManager] No template world for: " + mg.getName());
            callback.accept(null);
            return;
        }

        String templateName = template.getName();
        World.Environment env = template.getEnvironment();
        String newName = mg.getName() + "_" + UUID.randomUUID().toString().substring(0, 8);
        File src = template.getWorldFolder();
        File dst = new File(Bukkit.getWorldContainer(), newName);

        // 1. Save so all chunks are flushed to disk
        template.save();

        // 2. Unload the template (releases file locks)
        boolean unloaded = unloadTemplate(templateName);
        if (!unloaded) {
            plugin.getLogger().warning("[WorldManager] Could not unload template world '"
                    + templateName + "' — copy may fail on Windows.");
        }

        // 3. Copy async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyWorld(src, dst);
            } catch (IOException e) {
                plugin.getLogger().warning("[WorldManager] Copy failed: " + e.getMessage());
                // Reload template even if copy failed
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    reloadTemplate(templateName, env);
                    callback.accept(null);
                });
                return;
            }

            // 4 & 5. Back on main thread: reload template, then load the new copy
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                reloadTemplate(templateName, env);

                World world = Bukkit.createWorld(
                        new WorldCreator(newName)
                                .environment(env)
                                .generateStructures(false));
                if (world != null) {
                    world.setAutoSave(false);
                    world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
                    world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
                    world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, true);
                    tempWorlds.add(world);
                } else {
                    plugin.getLogger().warning("[WorldManager] Bukkit.createWorld returned null for " + newName);
                }
                callback.accept(world);
            });
        });
    }

    public void deleteWorld(World world) {
        if (world == null) return;
        for (Player p : world.getPlayers()) p.teleport(plugin.getLobbySpawn());
        Bukkit.unloadWorld(world, false);
        deleteFolder(world.getWorldFolder());
        tempWorlds.remove(world);
    }

    public void cleanup() {
        for (World w : new ArrayList<>(tempWorlds)) deleteWorld(w);
        tempWorlds.clear();
    }

    // -------------------------------------------------------------------------
    // Multiverse-aware unload / reload
    // -------------------------------------------------------------------------

    /**
     * Unloads the world. Uses Multiverse's MVWorldManager if available so MV
     * doesn't try to auto-reload it. Falls back to plain Bukkit.unloadWorld.
     * Returns true if the world was successfully unloaded.
     */
    private boolean unloadTemplate(String worldName) {
        // Try Multiverse first
        try {
            Class.forName(MV_CORE_CLASS);
            var mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mv != null && mv.isEnabled()) {
                var mvCore = Class.forName(MV_CORE_CLASS).cast(mv);
                var getMVWorldManager = mvCore.getClass().getMethod("getMVWorldManager");
                var mvwm = getMVWorldManager.invoke(mvCore);
                var unloadWorld = mvwm.getClass().getMethod("unloadWorld", String.class);
                Object result = unloadWorld.invoke(mvwm, worldName);
                if (Boolean.TRUE.equals(result)) {
                    plugin.getLogger().info("[WorldManager] Unloaded template '" + worldName + "' via Multiverse.");
                    return true;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Multiverse not installed — fall through to plain Bukkit
        } catch (Exception e) {
            plugin.getLogger().warning("[WorldManager] Multiverse unload failed, falling back: " + e.getMessage());
        }

        // Plain Bukkit fallback
        World w = Bukkit.getWorld(worldName);
        if (w == null) return true; // already unloaded
        // Move any players out first
        for (Player p : w.getPlayers()) p.teleport(plugin.getLobbySpawn());
        boolean ok = Bukkit.unloadWorld(w, true); // save=true to be safe
        if (ok) plugin.getLogger().info("[WorldManager] Unloaded template '" + worldName + "' via Bukkit.");
        return ok;
    }

    /**
     * Reloads the template world after copying. Uses Multiverse if available,
     * otherwise plain Bukkit.createWorld.
     */
    private void reloadTemplate(String worldName, World.Environment env) {
        // Try Multiverse first
        try {
            Class.forName(MV_CORE_CLASS);
            var mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mv != null && mv.isEnabled()) {
                var mvCore = Class.forName(MV_CORE_CLASS).cast(mv);
                var getMVWorldManager = mvCore.getClass().getMethod("getMVWorldManager");
                var mvwm = getMVWorldManager.invoke(mvCore);
                var loadWorld = mvwm.getClass().getMethod("loadWorld", String.class);
                Object result = loadWorld.invoke(mvwm, worldName);
                if (Boolean.TRUE.equals(result)) {
                    plugin.getLogger().info("[WorldManager] Reloaded template '" + worldName + "' via Multiverse.");
                    return;
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            plugin.getLogger().warning("[WorldManager] Multiverse reload failed, falling back: " + e.getMessage());
        }

        // Plain Bukkit fallback
        if (Bukkit.getWorld(worldName) == null) {
            Bukkit.createWorld(new WorldCreator(worldName).environment(env));
            plugin.getLogger().info("[WorldManager] Reloaded template '" + worldName + "' via Bukkit.");
        }
    }

    // -------------------------------------------------------------------------
    // File operations
    // -------------------------------------------------------------------------

    private void copyWorld(File src, File dst) throws IOException {
        Path srcPath = src.toPath();
        Path dstPath = dst.toPath();

        Files.walkFileTree(srcPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dstPath.resolve(srcPath.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (SKIP_FILES.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                Files.copy(file, dstPath.resolve(srcPath.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                plugin.getLogger().warning("[WorldManager] Skipped locked file: "
                        + file.getFileName() + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteFolder(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteFolder(c);
        }
        f.delete();
    }
}
