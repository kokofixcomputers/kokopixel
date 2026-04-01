package cc.kokodev.kokopixel.world;

import cc.kokodev.kokopixel.KokoPixel;
import cc.kokodev.kokopixel.minigames.Minigame;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WorldManager {
    private final KokoPixel plugin;
    private final List<World> tempWorlds = new ArrayList<>();

    public WorldManager(KokoPixel plugin) { this.plugin = plugin; }

    public World createGameWorld(Minigame mg) {
        World template = mg.getTemplateWorld();
        if (template == null) { plugin.getLogger().severe("No template world for: " + mg.getName()); return null; }
        String name = mg.getName() + "_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            copyWorld(template.getWorldFolder(), new File(Bukkit.getWorldContainer(), name));
            World world = Bukkit.createWorld(new WorldCreator(name).environment(template.getEnvironment()).generateStructures(false));
            if (world != null) {
                world.setAutoSave(false);
                world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
                world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
                world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, true);
                tempWorlds.add(world);
                return world;
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public void deleteWorld(World world) {
        if (world == null) return;
        for (Player p : world.getPlayers()) p.teleport(plugin.getLobbySpawn());
        Bukkit.unloadWorld(world, false);
        deleteFolder(world.getWorldFolder());
        tempWorlds.remove(world);
    }

    private void copyWorld(File src, File dst) throws IOException {
        if (!dst.exists()) dst.mkdirs();
        for (String f : src.list()) {
            File srcF = new File(src, f), dstF = new File(dst, f);
            if (srcF.isDirectory()) copyWorld(srcF, dstF);
            else try (InputStream in = new FileInputStream(srcF); OutputStream out = new FileOutputStream(dstF)) { byte[] buf = new byte[1024]; int len; while ((len = in.read(buf)) > 0) out.write(buf, 0, len); }
        }
    }

    private void deleteFolder(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteFolder(c);
        f.delete();
    }

    public void cleanup() { for (World w : new ArrayList<>(tempWorlds)) deleteWorld(w); tempWorlds.clear(); }
}