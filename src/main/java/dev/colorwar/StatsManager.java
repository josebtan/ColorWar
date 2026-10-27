package dev.colorwar;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StatsManager {

    public enum Category {
        WINS("Victorias", Material.GOLD_INGOT),
        KILLS("Eliminaciones", Material.DIAMOND_SWORD),
        BEST("Mejor % del mapa", Material.FILLED_MAP),
        GAMES("Partidas jugadas", Material.CLOCK);

        public final String label;
        public final Material icon;

        Category(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    public static class PlayerStats {
        public UUID id;
        public String name;
        public int wins, games, kills, deaths, coins;
        public double best;

        public double value(Category c) {
            return switch (c) {
                case WINS -> wins;
                case KILLS -> kills;
                case BEST -> best;
                case GAMES -> games;
            };
        }
    }

    private final ColorWarPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> data = new HashMap<>();

    public StatsManager(ColorWarPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                PlayerStats st = new PlayerStats();
                st.id = UUID.fromString(key);
                st.name = s.getString("name", "?");
                st.wins = s.getInt("wins");
                st.games = s.getInt("games");
                st.kills = s.getInt("kills");
                st.deaths = s.getInt("deaths");
                st.coins = s.getInt("coins");
                st.best = s.getDouble("best");
                data.put(st.id, st);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (PlayerStats st : data.values()) {
            String p = "players." + st.id;
            cfg.set(p + ".name", st.name);
            cfg.set(p + ".wins", st.wins);
            cfg.set(p + ".games", st.games);
            cfg.set(p + ".kills", st.kills);
            cfg.set(p + ".deaths", st.deaths);
            cfg.set(p + ".coins", st.coins);
            cfg.set(p + ".best", st.best);
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo guardar stats.yml: " + e.getMessage());
        }
    }

    public PlayerStats get(UUID id, String name) {
        PlayerStats st = data.computeIfAbsent(id, k -> {
            PlayerStats n = new PlayerStats();
            n.id = k;
            return n;
        });
        st.name = name;
        return st;
    }

    public List<PlayerStats> top(Category c, int n) {
        return data.values().stream()
                .filter(s -> s.value(c) > 0)
                .sorted((x, y) -> Double.compare(y.value(c), x.value(c)))
                .limit(n)
                .toList();
    }
}
