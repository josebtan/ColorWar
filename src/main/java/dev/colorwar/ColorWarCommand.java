package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ColorWarCommand implements CommandExecutor, TabCompleter {
    private static final Pattern BOT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final List<String> PLAYER_SUBS = List.of("menu", "join", "leave", "list", "top", "stats");
    private static final List<String> ADMIN_SUBS = List.of("admin", "debug", "create", "delete", "wand", "pos1", "pos2", "sel", "resize", "addspawn",
            "clearspawns", "setlobby", "setmainlobby", "set", "info", "forcestart", "forcestop", "reload");
    private static final List<String> SET_KEYS = List.of("mode", "spawnmode", "min", "max", "time", "teams", "enabled");

    private final ColorWarPlugin plugin;

    public ColorWarCommand(ColorWarPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) new Menus.Main(plugin).open(p);
            else help(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBS.contains(sub) && !s.hasPermission("colorwar.admin")) {
            Msg.send(s, "<red>No tienes permiso.");
            return true;
        }
        switch (sub) {
            case "menu" -> {
                Player p = player(s);
                if (p != null) new Menus.Main(plugin).open(p);
            }
            case "join" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 2) {
                    new Menus.Arenas(plugin).open(p);
                    return true;
                }
                Arena ar = arena(s, a[1]);
                if (ar == null) return true;
                String err = plugin.games().join(p, ar);
                if (err != null) Msg.send(p, "<red>" + err);
            }
            case "leave" -> {
                Player p = player(s);
                if (p == null) return true;
                if (plugin.games().leave(p)) Msg.send(p, "<gray>Has salido de la partida.");
                else Msg.send(p, "<red>No estás en ninguna partida.");
            }
            case "list" -> {
                Msg.send(s, "<yellow>Arenas:");
                for (Arena ar : plugin.arenas().all()) {
                    Game g = plugin.games().gameOf(ar);
                    String st = !ar.isReady() ? "<gray>no disponible" : g == null ? "<green>libre" : "<yellow>" + g.state;
                    s.sendMessage(Msg.parse("<dark_gray> - <white>" + ar.name + " <gray>(" + ar.mode + ") " + st));
                }
            }
            case "stats" -> {
                Player p = player(s);
                if (p != null) new Menus.Stats(plugin).open(p);
            }
            case "top" -> {
                Player p = player(s);
                if (p == null) return true;
                StatsManager.Category cat = StatsManager.Category.WINS;
                if (a.length > 1) {
                    try {
                        cat = StatsManager.Category.valueOf(a[1].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) { }
                }
                new Menus.Top(plugin, cat).open(p);
            }
            case "admin" -> {
                Player p = player(s);
                if (p != null) new Menus.Admin(plugin).open(p);
            }
            case "debug" -> {
                Player p = player(s);
                if (p == null) return true;
                Game cur = plugin.games().gameOf(p.getUniqueId());
                String arg = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (cur != null && cur.isDebug()) {
                    switch (arg) {
                        case "win" -> {
                            cur.debugFill(p, 100);
                            Msg.send(p, "<green>Mapa completado: ¡victoria!");
                        }
                        case "fill" -> {
                            double pct = 50;
                            if (a.length > 2) {
                                try {
                                    pct = Double.parseDouble(a.length > 2 ? a[2] : "50");
                                } catch (NumberFormatException ex) {
                                    Msg.send(p, "<red>Porcentaje no válido.");
                                    return true;
                                }
                            }
                            cur.debugFill(p, pct);
                            Msg.send(p, "<green>Mapa rellenado al " + pct + "%.");
                        }
                        case "addbot" -> {
                            String nm = a.length > 2 ? a[2] : null;
                            if (nm != null && !BOT_NAME.matcher(nm).matches()) {
                                Msg.send(p, "<red>Nombre no válido (letras, números y _, máx. 16).");
                                return true;
                            }
                            Integer lv = a.length > 3 ? parseLevel(a[3]) : Integer.valueOf(plugin.getConfig().getInt("debug.default-level", 2));
                            if (lv == null) {
                                Msg.send(p, "<red>Nivel no válido. Usa 1, 2 o 3 (fácil, normal, difícil).");
                                return true;
                            }
                            int team = 0;
                            if (a.length > 4) {
                                try {
                                    team = Integer.parseInt(a[4]);
                                } catch (NumberFormatException ex) {
                                    Msg.send(p, "<red>Número de equipo no válido.");
                                    return true;
                                }
                            }
                            String err = cur.debugAddBot(nm, team, lv);
                            if (err != null) Msg.send(p, "<red>" + err);
                        }
                        case "addbots" -> {
                            int n = 1;
                            if (a.length > 2) {
                                try {
                                    n = Math.max(1, Math.min(12, Integer.parseInt(a[2])));
                                } catch (NumberFormatException ex) {
                                    Msg.send(p, "<red>Cantidad no válida.");
                                    return true;
                                }
                            }
                            Integer lv = a.length > 3 ? parseLevel(a[3]) : Integer.valueOf(plugin.getConfig().getInt("debug.default-level", 2));
                            if (lv == null) {
                                Msg.send(p, "<red>Nivel no válido. Usa 1, 2 o 3 (fácil, normal, difícil).");
                                return true;
                            }
                            for (int i = 0; i < n; i++) {
                                String err = cur.debugAddBot(null, 0, lv);
                                if (err != null) {
                                    Msg.send(p, "<red>" + err);
                                    break;
                                }
                            }
                        }
                        case "setlevel" -> {
                            Integer lv = a.length > 3 ? parseLevel(a[3]) : null;
                            if (a.length < 4 || lv == null) {
                                Msg.send(p, "<red>Uso: /cw debug setlevel [nombre|todos] [1-3]");
                                return true;
                            }
                            String err = cur.debugSetLevel(a[2], lv);
                            Msg.send(p, err != null ? "<red>" + err : "<green>Nivel cambiado a " + Game.levelName(lv) + " " + Game.stars(lv));
                        }
                        case "removebot" -> {
                            if (a.length < 3) {
                                Msg.send(p, "<red>Uso: /cw debug removebot [nombre]");
                                return true;
                            }
                            String err = cur.debugRemoveBot(a[2]);
                            if (err != null) Msg.send(p, "<red>" + err);
                        }
                        case "die" -> cur.debugKill(p);
                        case "time" -> {
                            int secs = 10;
                            if (a.length > 2) {
                                try {
                                    secs = Integer.parseInt(a[2]);
                                } catch (NumberFormatException ex) {
                                    Msg.send(p, "<red>Segundos no válidos.");
                                    return true;
                                }
                            }
                            cur.debugSetTime(secs);
                            Msg.send(p, "<green>Quedan " + secs + "s.");
                        }
                        case "stop" -> {
                            cur.stopNow();
                            Msg.send(p, "<green>Debug detenido.");
                        }
                        case "info" -> {
                            for (String line : cur.debugInfo(p)) p.sendMessage(Msg.parse(line));
                        }
                        default -> debugHelp(p);
                    }
                    return true;
                }
                if (arg.isEmpty() || arg.equals("help")) {
                    debugHelp(p);
                    return true;
                }
                boolean watch = arg.equals("watch");
                int off = watch ? 1 : 0;
                if (a.length < 2 + off) {
                    Msg.send(p, "<red>Falta el nombre de la arena.");
                    return true;
                }
                Arena ar = arena(s, a[1 + off]);
                if (ar == null) return true;
                int bots = plugin.getConfig().getInt("debug.default-bots", 2);
                int defLevel = plugin.getConfig().getInt("debug.default-level", 2);
                if (a.length > 3 + off) {
                    Integer lv = parseLevel(a[3 + off]);
                    if (lv == null) {
                        Msg.send(p, "<red>Nivel no válido. Usa 1, 2 o 3 (fácil, normal, difícil).");
                        return true;
                    }
                    defLevel = lv;
                }
                List<Game.BotSpec> names = new ArrayList<>();
                if (a.length > 2 + off) {
                    if (a[2 + off].matches("\\d+")) {
                        bots = Integer.parseInt(a[2 + off]);
                    } else {
                        for (String token : a[2 + off].split(",")) {
                            String[] parts = token.split(":");
                            Integer lv = parts.length > 1 ? parseLevel(parts[1]) : Integer.valueOf(defLevel);
                            if (!BOT_NAME.matcher(parts[0]).matches() || lv == null) {
                                Msg.send(p, "<red>Bot no válido: " + token + " <gray>(formato nombre o nombre:nivel)");
                                return true;
                            }
                            names.add(new Game.BotSpec(parts[0], lv));
                        }
                    }
                }
                String err = plugin.games().joinDebug(p, ar, bots, names, defLevel, watch);
                if (err != null) Msg.send(p, "<red>" + err);
                else Msg.send(p, watch
                        ? "<yellow>Observando una partida solo de bots. <gray>Otros jugadores pueden entrar con /cw join " + ar.name + ". Sal con /cw leave."
                        : "<yellow>Modo debug iniciado. <gray>Otros jugadores pueden entrar con /cw join " + ar.name + ". Más pruebas: /cw debug");
            }
            case "create" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 3) {
                    Msg.send(s, "<red>Uso: /cw create [nombre] [solo|team]");
                    return true;
                }
                if (!NAME.matcher(a[1]).matches()) {
                    Msg.send(s, "<red>Nombre no válido (usa letras, números, _ y -, máximo 16).");
                    return true;
                }
                if (plugin.arenas().get(a[1]) != null) {
                    Msg.send(s, "<red>Ya existe una arena con ese nombre.");
                    return true;
                }
                Arena.PlayMode mode = parseMode(a[2]);
                if (mode == null) {
                    Msg.send(s, "<red>Tipo no válido. Usa <white>solo</white> o <white>team</white>.");
                    return true;
                }
                Selection sel = plugin.selections().get(p.getUniqueId());
                String err = sel == null ? "Primero marca el área con el hacha de madera (usa /cw wand)." : sel.problem();
                if (err != null) {
                    Msg.send(s, "<red>No se puede crear: " + err);
                    return true;
                }
                Arena ar = new Arena(a[1]);
                sel.applyTo(ar);
                ar.mode = mode;
                plugin.arenas().add(ar);
                Msg.send(s, "<green>Arena <white>" + ar.name + "<green> creada (" + ar.width() + "x" + ar.depth()
                        + ", modo " + (mode == Arena.PlayMode.SOLO ? "solo" : "equipos") + "). "
                        + "<gray>Construyendo suelo y bordes...");
                final String arenaName = ar.name;
                ArenaBuilder.build(plugin, ar, () -> {
                    if (p.isOnline()) Msg.send(p, "<green>Suelo y bordes listos. <gray>Entra con /cw join " + arenaName
                            + ". Más ajustes: /cw admin");
                });
            }
            case "delete" -> {
                Arena ar = need(s, a);
                if (ar == null) return true;
                plugin.deleteArena(ar);
                Msg.send(s, "<green>Arena eliminada.");
            }
            case "wand" -> {
                Player p = player(s);
                if (p == null) return true;
                p.getInventory().addItem(Selection.wand());
                Msg.send(p, "<green>Hacha entregada. <gray>Clic izquierdo = pos1, clic derecho = pos2, sobre dos esquinas del suelo.");
            }
            case "pos1", "pos2" -> {
                Player p = player(s);
                if (p == null) return true;
                boolean first = sub.equals("pos1");
                Location l = p.getLocation();
                Selection sel = plugin.selections().getOrCreate(p.getUniqueId());
                sel.set(first, l.getWorld().getName(), l.getBlockX(), l.getBlockY() - 1, l.getBlockZ());
                Msg.send(p, "<yellow>" + (first ? "Pos1" : "Pos2") + " marcada. " + sel.status());
            }
            case "sel" -> {
                Player p = player(s);
                if (p == null) return true;
                Selection sel = plugin.selections().get(p.getUniqueId());
                Msg.send(p, sel == null ? "<gray>No tienes ninguna selección. Usa /cw wand." : sel.status());
            }
            case "resize" -> {
                Player p = player(s);
                Arena ar = need(s, a);
                if (p == null || ar == null) return true;
                if (plugin.games().gameOf(ar) != null) {
                    Msg.send(s, "<red>La arena está en uso.");
                    return true;
                }
                Selection sel = plugin.selections().get(p.getUniqueId());
                String err = sel == null ? "Primero marca el área con el hacha de madera." : sel.problem();
                if (err != null) {
                    Msg.send(s, "<red>" + err);
                    return true;
                }
                Msg.send(s, "<gray>Reconstruyendo suelo y bordes...");
                ArenaBuilder.clearBorder(plugin, ar, () -> {
                    sel.applyTo(ar);
                    plugin.arenas().save();
                    ArenaBuilder.build(plugin, ar, () -> {
                        if (p.isOnline()) Msg.send(p, "<green>Área de <white>" + ar.name + "<green> actualizada ("
                                + ar.width() + "x" + ar.depth() + ").");
                    });
                });
            }
            case "addspawn" -> {
                Player p = player(s);
                Arena ar = need(s, a);
                if (p == null || ar == null) return true;
                ar.spawns.add(p.getLocation());
                plugin.arenas().save();
                Msg.send(s, "<green>Spawn #" + ar.spawns.size() + " añadido. <gray>Recuerda: /cw set " + ar.name
                        + " spawnmode defined");
            }
            case "clearspawns" -> {
                Arena ar = need(s, a);
                if (ar == null) return true;
                ar.spawns.clear();
                plugin.arenas().save();
                Msg.send(s, "<green>Spawns borrados.");
            }
            case "setlobby" -> {
                Player p = player(s);
                Arena ar = need(s, a);
                if (p == null || ar == null) return true;
                ar.lobby = p.getLocation();
                plugin.arenas().save();
                Msg.send(s, "<green>Sala de espera de la arena guardada.");
            }
            case "setmainlobby" -> {
                Player p = player(s);
                if (p == null) return true;
                plugin.arenas().setMainLobby(p.getLocation());
                Msg.send(s, "<green>Lobby principal guardado (a donde vuelven los jugadores).");
            }
            case "set" -> handleSet(s, a);
            case "info" -> {
                Arena ar = need(s, a);
                if (ar == null) return true;
                String prob = ar.problem();
                Msg.send(s, "<yellow>" + ar.name + ": <gray>modo <white>" + ar.mode + "<gray>, spawns <white>" + ar.spawnMode
                        + " (" + ar.spawns.size() + ")<gray>, jugadores <white>" + ar.minPlayers + "-" + ar.maxPlayers
                        + "<gray>, tiempo <white>" + ar.duration + "s<gray>, equipos <white>" + ar.teams
                        + "<gray>, activa <white>" + ar.enabled);
                Msg.send(s, prob == null ? "<green>Configuración correcta (" + ar.width() + "x" + ar.depth() + ")."
                        : "<red>" + prob);
            }
            case "forcestart" -> {
                Arena ar = need(s, a);
                if (ar == null) return true;
                Game g = plugin.games().gameOf(ar);
                Msg.send(s, g != null && g.forceStart() ? "<green>Partida iniciada." : "<red>No se pudo iniciar.");
            }
            case "forcestop" -> {
                Arena ar = need(s, a);
                if (ar == null) return true;
                Game g = plugin.games().gameOf(ar);
                if (g == null) {
                    Msg.send(s, "<red>No hay partida activa.");
                } else {
                    g.stopNow();
                    Msg.send(s, "<green>Partida detenida.");
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                Msg.send(s, "<green>Configuración recargada.");
            }
            default -> help(s);
        }
        return true;
    }

    private void handleSet(CommandSender s, String[] a) {
        if (a.length < 4) {
            Msg.send(s, "<red>Uso: /cw set [arena] [" + String.join("|", SET_KEYS) + "] [valor]");
            return;
        }
        Arena ar = arena(s, a[1]);
        if (ar == null) return;
        String key = a[2].toLowerCase(Locale.ROOT), val = a[3].toUpperCase(Locale.ROOT);
        try {
            switch (key) {
                case "mode" -> ar.mode = Arena.PlayMode.valueOf(val);
                case "spawnmode" -> ar.spawnMode = Arena.SpawnMode.valueOf(val);
                case "min" -> ar.minPlayers = Math.max(1, Math.min(ar.maxPlayers, Integer.parseInt(val)));
                case "max" -> ar.maxPlayers = Math.max(ar.minPlayers, Math.min(Colors.PALETTE.size(), Integer.parseInt(val)));
                case "time" -> ar.duration = Math.max(30, Math.min(1800, Integer.parseInt(val)));
                case "teams" -> ar.teams = Math.max(2, Math.min(8, Integer.parseInt(val)));
                case "enabled" -> ar.enabled = Boolean.parseBoolean(val);
                default -> {
                    Msg.send(s, "<red>Opción desconocida. Usa: " + String.join(", ", SET_KEYS));
                    return;
                }
            }
        } catch (IllegalArgumentException ex) {
            Msg.send(s, "<red>Valor no válido.");
            return;
        }
        plugin.arenas().save();
        Msg.send(s, "<green>" + key + " actualizado.");
    }

    // ---------------------------------------------------------------- utils

    private void debugHelp(Player p) {
        Msg.send(p, "<yellow>Debug: <white>/cw debug [arena] [bots o nombres] [nivel]</white> <gray>ej: /cw debug mapa1 3 2  o  /cw debug mapa1 Pepe:1,Luis:3");
        Msg.send(p, "<gray>Bots: <white>/cw debug addbot [nombre] [nivel] [equipo] | addbots [n] [nivel] | setlevel [nombre|todos] [nivel] | removebot [nombre]");
        Msg.send(p, "<gray>Solo bots: <white>/cw debug watch [arena] [bots] [nivel]</white> <gray>(tú observas; otros pueden entrar con /cw join)");
        Msg.send(p, "<gray>Niveles: <white>1 fácil ★ · 2 normal ★★ · 3 difícil ★★★");
        Msg.send(p, "<gray>Pruebas: <white>/cw debug win | fill [%] | die | time [seg] | info | stop");
    }

    private void help(CommandSender s) {
        Msg.send(s, "<yellow>Comandos: <white>/cw, /cw join [arena], /cw leave, /cw list, /cw top, /cw stats");
        if (s.hasPermission("colorwar.admin")) {
            Msg.send(s, "<yellow>Admin: <white>/cw admin | wand | create | delete | resize | sel | addspawn | clearspawns | "
                    + "setlobby | setmainlobby | set | info | forcestart | forcestop | reload");
        }
    }

    /** 1-3 o fácil/normal/difícil (también easy/medium/hard). */
    private Integer parseLevel(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "1", "facil", "fácil", "easy" -> 1;
            case "2", "normal", "medio", "medium" -> 2;
            case "3", "dificil", "difícil", "hard" -> 3;
            default -> null;
        };
    }

    private Arena.PlayMode parseMode(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "solo" -> Arena.PlayMode.SOLO;
            case "team", "teams", "equipo", "equipos" -> Arena.PlayMode.TEAM;
            default -> null;
        };
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        Msg.send(s, "<red>Solo jugadores pueden usar este comando.");
        return null;
    }

    private Arena need(CommandSender s, String[] a) {
        if (a.length < 2) {
            Msg.send(s, "<red>Falta el nombre de la arena.");
            return null;
        }
        return arena(s, a[1]);
    }

    private Arena arena(CommandSender s, String name) {
        Arena ar = plugin.arenas().get(name);
        if (ar == null) Msg.send(s, "<red>No existe la arena <white>" + name + "<red>.");
        return ar;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (s.hasPermission("colorwar.admin")) out.addAll(ADMIN_SUBS);
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("debug")) {
                out.addAll(List.of("win", "fill", "die", "time", "info", "stop", "addbot", "addbots", "removebot", "setlevel", "watch"));
                for (Arena ar : plugin.arenas().all()) out.add(ar.name);
            } else if (sub.equals("top")) {
                for (StatsManager.Category cat : StatsManager.Category.values()) out.add(cat.name().toLowerCase(Locale.ROOT));
            } else if (!List.of("create", "list", "menu", "wand", "pos1", "pos2", "sel", "setmainlobby", "reload").contains(sub)) {
                for (Arena ar : plugin.arenas().all()) out.add(ar.name);
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("debug") && (a[1].equalsIgnoreCase("removebot") || a[1].equalsIgnoreCase("setlevel"))
                && s instanceof Player pl) {
            Game g = plugin.games().gameOf(pl.getUniqueId());
            if (g != null) out.addAll(g.botNames());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("debug") && a[1].equalsIgnoreCase("watch")) {
            for (Arena ar : plugin.arenas().all()) out.add(ar.name);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.addAll(List.of("solo", "team"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
            out.addAll(SET_KEYS);
        } else if (a.length == 4 && a[0].equalsIgnoreCase("set")) {
            switch (a[2].toLowerCase(Locale.ROOT)) {
                case "mode" -> out.addAll(List.of("solo", "team"));
                case "spawnmode" -> out.addAll(List.of("random", "defined"));
                case "enabled" -> out.addAll(List.of("true", "false"));
                default -> { }
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
