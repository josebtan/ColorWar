package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public class Arena {
    public enum PlayMode { SOLO, TEAM }
    public enum SpawnMode { RANDOM, DEFINED }

    public static final int MIN_SIZE = 10, MAX_SIZE = 200;

    public final String name;
    public String world;
    public boolean p1, p2;
    public int x1, z1, x2, z2, floorY;
    public PlayMode mode = PlayMode.SOLO;
    public SpawnMode spawnMode = SpawnMode.RANDOM;
    public int minPlayers = 2, maxPlayers = 8, duration = 180, teams = 2;
    public boolean enabled = true;
    public final List<Location> spawns = new ArrayList<>();
    public Location lobby;

    public Arena(String name) {
        this.name = name;
    }

    public int minX() { return Math.min(x1, x2); }
    public int maxX() { return Math.max(x1, x2); }
    public int minZ() { return Math.min(z1, z2); }
    public int maxZ() { return Math.max(z1, z2); }
    public int width() { return Math.abs(x1 - x2) + 1; }
    public int depth() { return Math.abs(z1 - z2) + 1; }

    /** Devuelve null si la arena está bien configurada, o el motivo del problema. */
    public String problem() {
        if (!p1 || !p2) return "Faltan las posiciones (usa /cw pos1 y /cw pos2).";
        if (world == null || Bukkit.getWorld(world) == null) return "El mundo no está cargado.";
        if (width() < MIN_SIZE || depth() < MIN_SIZE) return "La arena debe medir al menos 10x10.";
        if (width() > MAX_SIZE || depth() > MAX_SIZE) return "La arena no puede superar 200x200.";
        return null;
    }

    public boolean isReady() {
        return enabled && problem() == null;
    }

    public Location centerTop() {
        World w = Bukkit.getWorld(world);
        return new Location(w, (minX() + maxX() + 1) / 2.0, floorY + 1.0, (minZ() + maxZ() + 1) / 2.0);
    }

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("world", world);
        s.set("p1", p1);
        s.set("p2", p2);
        s.set("x1", x1);
        s.set("z1", z1);
        s.set("x2", x2);
        s.set("z2", z2);
        s.set("floorY", floorY);
        s.set("mode", mode.name());
        s.set("spawnMode", spawnMode.name());
        s.set("minPlayers", minPlayers);
        s.set("maxPlayers", maxPlayers);
        s.set("duration", duration);
        s.set("teams", teams);
        s.set("enabled", enabled);
        s.set("spawns", spawns);
        s.set("lobby", lobby);
    }

    public static Arena load(ConfigurationSection s) {
        Arena a = new Arena(s.getString("name", s.getName()));
        a.world = s.getString("world");
        a.p1 = s.getBoolean("p1");
        a.p2 = s.getBoolean("p2");
        a.x1 = s.getInt("x1");
        a.z1 = s.getInt("z1");
        a.x2 = s.getInt("x2");
        a.z2 = s.getInt("z2");
        a.floorY = s.getInt("floorY");
        try {
            a.mode = PlayMode.valueOf(s.getString("mode", "SOLO"));
        } catch (IllegalArgumentException ignored) { }
        try {
            a.spawnMode = SpawnMode.valueOf(s.getString("spawnMode", "RANDOM"));
        } catch (IllegalArgumentException ignored) { }
        a.minPlayers = s.getInt("minPlayers", 2);
        a.maxPlayers = s.getInt("maxPlayers", 8);
        a.duration = s.getInt("duration", 180);
        a.teams = s.getInt("teams", 2);
        a.enabled = s.getBoolean("enabled", true);
        for (Object o : s.getList("spawns", new ArrayList<>())) {
            if (o instanceof Location l) a.spawns.add(l);
        }
        a.lobby = s.getLocation("lobby");
        return a;
    }
}
