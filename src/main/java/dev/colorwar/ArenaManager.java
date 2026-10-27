package dev.colorwar;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class ArenaManager {
    private final ColorWarPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private Location mainLobby;

    public ArenaManager(ColorWarPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        arenas.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        mainLobby = cfg.getLocation("main-lobby");
        ConfigurationSection sec = cfg.getConfigurationSection("arenas");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            Arena a = Arena.load(s);
            arenas.put(a.name.toLowerCase(), a);
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("main-lobby", mainLobby);
        for (Arena a : arenas.values()) {
            a.save(cfg.createSection("arenas." + a.name.toLowerCase()));
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo guardar arenas.yml: " + e.getMessage());
        }
    }

    public Arena get(String name) { return name == null ? null : arenas.get(name.toLowerCase()); }
    public Collection<Arena> all() { return arenas.values(); }
    public void add(Arena a) { arenas.put(a.name.toLowerCase(), a); save(); }
    public void remove(Arena a) { arenas.remove(a.name.toLowerCase()); save(); }
    public Location mainLobby() { return mainLobby; }
    public void setMainLobby(Location l) { mainLobby = l; save(); }
}
