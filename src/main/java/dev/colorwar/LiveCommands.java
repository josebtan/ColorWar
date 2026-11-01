package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /cw live [acción] [streamer] ...  Pensado para ejecutarse desde la consola con ChatPointsTTV
 * (RUN CONSOLE cw live ...): cada evento de TikTok actúa sobre la partida del streamer.
 */
public final class LiveCommands {
    public static final List<String> ACTIONS = List.of("speed", "slow", "shield", "freeze", "grow",
            "addbot", "removebot", "killbot", "botlevel", "slowbots", "freezebots");

    private LiveCommands() {}

    static void handle(ColorWarPlugin plugin, CommandSender s, String[] a) {
        if (!plugin.getConfig().getBoolean("live.enabled", true)) {
            Msg.send(s, "<red>Los eventos en directo están desactivados (live.enabled: false).");
            return;
        }
        if (a.length < 3) {
            usage(s);
            return;
        }
        String action = a[1].toLowerCase(Locale.ROOT);
        Player streamer = Bukkit.getPlayerExact(a[2]);
        if (streamer == null) {
            Msg.send(s, "<red>El jugador <n> no está conectado.", Msg.p("n", a[2]));
            return;
        }
        Game g = plugin.games().gameOf(streamer.getUniqueId());
        if (g == null) {
            Msg.send(s, "<yellow><n> no está en una partida de ColorWar: evento ignorado.", Msg.p("n", a[2]));
            return;
        }
        String err;
        switch (action) {
            case "speed" -> err = g.liveSpeed(streamer, intArg(a, 3, 1), intArg(a, 4, 10));
            case "slow" -> err = g.liveSlow(streamer, intArg(a, 3, 1), intArg(a, 4, 8));
            case "shield" -> err = g.liveShield(streamer, intArg(a, 3, 10));
            case "freeze" -> err = g.liveFreeze(streamer, intArg(a, 3, 5));
            case "grow" -> {
                // grow [streamer] [porcentaje] [veces]: el porcentaje se multiplica por "veces" (por ejemplo {AMOUNT})
                double pct = dblArg(a, 3, 2.0) * Math.max(1, Math.min(10, intArg(a, 4, 1)));
                err = g.liveGrow(streamer, pct);
            }
            case "addbot" -> {
                // addbot [streamer] [nivel 1-3] [ally|enemy|auto] [cantidad] [nombre...]
                if (a.length < 7) {
                    Msg.send(s, "<red>Uso: /cw live addbot [streamer] [nivel] [ally|enemy|auto] [cantidad] [nombre]");
                    return;
                }
                int level = intArg(a, 3, 2);
                String mode = modeArg(a[4]);
                int count = Math.max(1, Math.min(10, intArg(a, 5, 1)));
                String donor = String.join(" ", Arrays.copyOfRange(a, 6, a.length));
                err = null;
                for (int i = 0; i < count && err == null; i++) err = g.liveAddBot(streamer, level, mode, donor);
            }
            case "removebot" -> err = a.length < 4 ? "Falta el nombre del bot."
                    : g.debugRemoveBot(String.join(" ", Arrays.copyOfRange(a, 3, a.length)));
            case "killbot" -> {
                int count = Math.max(1, Math.min(10, intArg(a, 3, 1)));
                err = null;
                for (int i = 0; i < count && err == null; i++) err = g.liveKillBot(streamer);
            }
            case "botlevel" -> err = g.liveBotLevel(streamer, intArg(a, 3, 3), a.length > 4 ? a[4] : null);
            case "slowbots" -> err = g.liveSlowBots(streamer, intArg(a, 3, 10), false);
            case "freezebots" -> err = g.liveSlowBots(streamer, intArg(a, 3, 8), true);
            default -> {
                usage(s);
                return;
            }
        }
        if (err != null) Msg.send(s, "<yellow>" + err);
    }

    private static int intArg(String[] a, int i, int def) {
        if (a.length <= i) return def;
        try {
            return Integer.parseInt(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double dblArg(String[] a, int i, double def) {
        if (a.length <= i) return def;
        try {
            return Double.parseDouble(a[i]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String modeArg(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "ally", "aliado", "amigo" -> "ally";
            case "enemy", "enemigo", "rival" -> "enemy";
            default -> "auto";
        };
    }

    static void usage(CommandSender s) {
        Msg.send(s, "<yellow>Eventos en directo: <white>/cw live [acción] [streamer] ...");
        String[] lines = {
                "<white>speed [streamer] [nivel 1-5] [seg]</white> <gray>velocidad temporal",
                "<white>slow [streamer] [nivel 1-5] [seg]</white> <gray>lentitud temporal",
                "<white>shield [streamer] [seg]</white> <gray>su estela no se puede cortar",
                "<white>freeze [streamer] [seg]</white> <gray>no se puede mover",
                "<white>grow [streamer] [%] [veces]</white> <gray>regala territorio",
                "<white>addbot [streamer] [nivel 1-3] [ally|enemy|auto] [cantidad] [nombre]</white> <gray>bot con el nombre del espectador",
                "<white>removebot [streamer] [nombre]</white> <gray>quita un bot",
                "<white>killbot [streamer] [cantidad]</white> <gray>elimina bots rivales al azar",
                "<white>botlevel [streamer] [nivel] (nombre|todos)</white> <gray>cambia el nivel de los bots rivales",
                "<white>slowbots / freezebots [streamer] [seg]</white> <gray>ralentiza o congela a los bots rivales"
        };
        for (String line : lines) s.sendMessage(Msg.parse(line));
    }
}
