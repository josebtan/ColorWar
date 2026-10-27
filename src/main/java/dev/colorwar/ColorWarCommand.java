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
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final List<String> PLAYER_SUBS = List.of("menu", "join", "leave", "list", "top", "stats");
    private static final List<String> ADMIN_SUBS = List.of("admin", "create", "delete", "pos1", "pos2", "addspawn",
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
            case "create" -> {
                if (a.length < 2 || !NAME.matcher(a[1]).matches()) {
                    Msg.send(s, "<red>Uso: /cw create <nombre> <gray>(letras, números, _ y -)");
                    return true;
                }
                if (plugin.arenas().get(a[1]) != null) {
                    Msg.send(s, "<red>Ya existe esa arena.");
                    return true;
                }
                plugin.arenas().add(new Arena(a[1]));
                Msg.send(s, "<green>Arena <white>" + a[1] + "<green> creada. Ahora usa <white>/cw pos1 " + a[1]
                        + "<green> y <white>/cw pos2 " + a[1] + "<green> parado en dos esquinas del suelo.");
            }
            case "delete" -> {
                Arena ar = need(s, a);
                if (ar == null) return true;
                Game g = plugin.games().gameOf(ar);
                if (g != null) g.stopNow();
                plugin.arenas().remove(ar);
                Msg.send(s, "<green>Arena eliminada.");
            }
            case "pos1", "pos2" -> {
                Player p = player(s);
                Arena ar = need(s, a);
                if (p == null || ar == null) return true;
                Location l = p.getLocation();
                ar.world = l.getWorld().getName();
                if (sub.equals("pos1")) {
                    ar.x1 = l.getBlockX();
                    ar.z1 = l.getBlockZ();
                    ar.floorY = l.getBlockY() - 1;   // el bloque que pisas es el suelo de la arena
                    ar.p1 = true;
                } else {
                    ar.x2 = l.getBlockX();
                    ar.z2 = l.getBlockZ();
                    ar.p2 = true;
                }
                plugin.arenas().save();
                String prob = ar.problem();
                Msg.send(s, "<green>" + sub + " guardada." + (prob == null
                        ? " <gray>Tamaño: <white>" + ar.width() + "x" + ar.depth() : " <gray>" + prob));
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
            Msg.send(s, "<red>Uso: /cw set <arena> <" + String.join("|", SET_KEYS) + "> <valor>");
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

    private void help(CommandSender s) {
        Msg.send(s, "<yellow>Comandos: <white>/cw [menu|join <arena>|leave|list|top|stats]");
        if (s.hasPermission("colorwar.admin")) {
            Msg.send(s, "<yellow>Admin: <white>/cw admin | create | delete | pos1 | pos2 | addspawn | clearspawns | "
                    + "setlobby | setmainlobby | set | info | forcestart | forcestop | reload");
        }
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
            if (sub.equals("top")) {
                for (StatsManager.Category cat : StatsManager.Category.values()) out.add(cat.name().toLowerCase(Locale.ROOT));
            } else if (!sub.equals("create") && !sub.equals("list") && !sub.equals("menu")) {
                for (Arena ar : plugin.arenas().all()) out.add(ar.name);
            }
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
