package dev.colorwar;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GameManager {
    private final ColorWarPlugin plugin;
    private final Map<String, Game> games = new HashMap<>();
    private final Map<UUID, Game> byPlayer = new HashMap<>();

    public GameManager(ColorWarPlugin plugin) {
        this.plugin = plugin;
    }

    public Game gameOf(UUID id) { return byPlayer.get(id); }
    public int playersInGames() { return byPlayer.size(); }
    public int gamesRunning() { return games.size(); }
    public Game gameOf(Arena a) { return games.get(a.name.toLowerCase()); }
    void register(UUID id, Game g) { byPlayer.put(id, g); }
    void unregister(UUID id) { byPlayer.remove(id); }

    void removeGame(Game g) {
        games.remove(g.arena.name.toLowerCase(), g);
        byPlayer.values().removeIf(x -> x == g);
    }

    /** Devuelve null si todo fue bien, o un mensaje de error. */
    public String join(Player p, Arena a) {
        if (byPlayer.containsKey(p.getUniqueId())) return "Ya estás en una partida. Usa /cw leave.";
        if (!a.isReady()) return "Esa arena no está disponible.";
        Game g = games.get(a.name.toLowerCase());
        if (g == null) {
            g = new Game(plugin, a);
            games.put(a.name.toLowerCase(), g);
        }
        boolean debugOpen = g.isDebug() && g.state == Game.State.RUNNING;   // las de debug admiten entrada tardía
        if (g.state != Game.State.WAITING && g.state != Game.State.COUNTDOWN && !debugOpen) {
            return "La partida ya está en curso o reiniciándose.";
        }
        if (!debugOpen && g.playerCount() >= g.maxPlayers()) return "La arena está llena.";
        g.addPlayer(p);
        return null;
    }

    /** Partida de prueba en solitario: sin cuenta atrás, con bots, sin estadísticas ni premios. */
    public String joinDebug(Player p, Arena a, int bots, java.util.List<Game.BotSpec> specs, int level) {
        return joinDebug(p, a, bots, specs, level, false);
    }

    /** watch = true: partida solo de bots; quien la inicia la observa sin participar. */
    public String joinDebug(Player p, Arena a, int bots, java.util.List<Game.BotSpec> specs, int level, boolean watch) {
        if (byPlayer.containsKey(p.getUniqueId())) return "Ya estás en una partida. Usa /cw leave.";
        if (!a.isReady()) return "Esa arena no está disponible.";
        if (games.containsKey(a.name.toLowerCase())) {
            return "La arena está en uso. Espera o usa /cw forcestop " + a.name + ".";
        }
        Game g = new Game(plugin, a);
        g.setDebug(true);
        games.put(a.name.toLowerCase(), g);
        if (watch) g.addSpectator(p);
        else g.addPlayer(p);
        int count = Math.max(0, bots);
        if (watch && (specs == null || specs.isEmpty())) count = Math.max(2, count);
        g.addBots(count, specs, level);
        g.forceStart();
        return null;
    }

    public boolean leave(Player p) {
        Game g = byPlayer.get(p.getUniqueId());
        if (g == null) return false;
        g.removePlayer(p);
        return true;
    }

    public void shutdown() {
        for (Game g : new ArrayList<>(games.values())) g.stopNow();
        games.clear();
        byPlayer.clear();
    }
}
