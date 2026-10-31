package dev.colorwar;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Placeholders de PlaceholderAPI: %colorwar_...% (clasificaciones para hologramas, estadísticas y arenas). */
public class ColorWarExpansion extends PlaceholderExpansion {
    private static final long CACHE_MS = 3000;
    private static final int MAX_POS = 20;

    private final ColorWarPlugin plugin;
    private final Map<StatsManager.Category, List<StatsManager.PlayerStats>> cache = new EnumMap<>(StatsManager.Category.class);
    private long cacheAt;

    public ColorWarExpansion(ColorWarPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "colorwar"; }
    @Override public String getAuthor() { return "ColorWar"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String raw = params.toLowerCase(Locale.ROOT);
        String[] p = raw.split("_");
        if (p.length == 0) return null;
        return switch (p[0]) {
            case "top" -> top(p);
            case "arena" -> p.length == 1 ? playerArena(player) : arenaInfo(raw.substring("arena_".length()));
            case "ingame" -> player != null && plugin.games().gameOf(player.getUniqueId()) != null ? "true" : "false";
            case "players" -> p.length == 2 && p[1].equals("total") ? String.valueOf(plugin.games().playersInGames()) : null;
            case "games" -> p.length == 2 && p[1].equals("running") ? String.valueOf(plugin.games().gamesRunning()) : stat(player, "games");
            case "wins", "kills", "deaths", "coins", "best", "winrate", "kdr" -> stat(player, p[0]);
            default -> null;
        };
    }

    // ------------------------------------------------------------ clasificación

    private String top(String[] p) {
        if (p.length < 3) return null;
        StatsManager.Category cat = category(p[1]);
        int pos;
        try {
            pos = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (cat == null || pos < 1 || pos > MAX_POS) return null;
        String field = p.length > 3 ? p[3] : "line";
        List<StatsManager.PlayerStats> list = cachedTop(cat);
        StatsManager.PlayerStats st = pos <= list.size() ? list.get(pos - 1) : null;
        String empty = plugin.getConfig().getString("placeholders.empty", "---");
        return switch (field) {
            case "name" -> st == null ? empty : st.name;
            case "value" -> st == null ? "0" : format(st, cat);
            case "line" -> st == null ? empty : st.name + " - " + format(st, cat);
            default -> null;
        };
    }

    private static StatsManager.Category category(String s) {
        return switch (s) {
            case "wins" -> StatsManager.Category.WINS;
            case "kills" -> StatsManager.Category.KILLS;
            case "best" -> StatsManager.Category.BEST;
            case "games" -> StatsManager.Category.GAMES;
            default -> null;
        };
    }

    private static String format(StatsManager.PlayerStats st, StatsManager.Category cat) {
        double v = st.value(cat);
        return cat == StatsManager.Category.BEST ? String.format(Locale.US, "%.1f%%", v) : String.valueOf((int) v);
    }

    private synchronized List<StatsManager.PlayerStats> cachedTop(StatsManager.Category cat) {
        long now = System.currentTimeMillis();
        if (now - cacheAt > CACHE_MS) {
            cache.clear();
            cacheAt = now;
        }
        return cache.computeIfAbsent(cat, c -> plugin.stats().top(c, MAX_POS));
    }

    // ---------------------------------------------------------- jugador y arenas

    private String stat(OfflinePlayer player, String key) {
        StatsManager.PlayerStats st = player == null ? null : plugin.stats().find(player.getUniqueId());
        if (st == null) return key.equals("best") ? "0.0%" : (key.equals("winrate") ? "0.0" : (key.equals("kdr") ? "0.00" : "0"));
        return switch (key) {
            case "wins" -> String.valueOf(st.wins);
            case "games" -> String.valueOf(st.games);
            case "kills" -> String.valueOf(st.kills);
            case "deaths" -> String.valueOf(st.deaths);
            case "coins" -> String.valueOf(st.coins);
            case "best" -> String.format(Locale.US, "%.1f%%", st.best);
            case "winrate" -> String.format(Locale.US, "%.1f", st.games == 0 ? 0.0 : st.wins * 100.0 / st.games);
            case "kdr" -> String.format(Locale.US, "%.2f", st.deaths == 0 ? (double) st.kills : st.kills / (double) st.deaths);
            default -> null;
        };
    }

    private String playerArena(OfflinePlayer player) {
        if (player == null) return "";
        Game g = plugin.games().gameOf(player.getUniqueId());
        return g == null ? "" : g.arena.name;
    }

    /** arena_[nombre]_[status|players|max|min|mode]; el nombre puede llevar guiones bajos. */
    private String arenaInfo(String rest) {
        int i = rest.lastIndexOf('_');
        if (i <= 0) return null;
        Arena a = plugin.arenas().get(rest.substring(0, i));
        if (a == null) return null;
        Game g = plugin.games().gameOf(a);
        return switch (rest.substring(i + 1)) {
            case "status" -> {
                if (!a.isReady()) yield "No disponible";
                if (g == null) yield "Libre";
                yield switch (g.state) {
                    case WAITING -> "Esperando";
                    case COUNTDOWN -> "Empezando";
                    case RUNNING -> "En juego";
                    case ENDING -> "Terminando";
                };
            }
            case "players" -> String.valueOf(g == null ? 0 : g.playerCount());
            case "max" -> String.valueOf(Math.min(a.maxPlayers, Colors.PALETTE.size()));
            case "min" -> String.valueOf(a.minPlayers);
            case "mode" -> a.mode == Arena.PlayMode.SOLO ? "Solo" : "Equipos";
            default -> null;
        };
    }
}
