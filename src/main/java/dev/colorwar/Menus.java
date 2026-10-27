package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Locale;

/** Todos los menús GUI del plugin. */
public final class Menus {
    private Menus() {}

    // ------------------------------------------------------------ principal
    public static class Main extends Menu {
        public Main(ColorWarPlugin pl) {
            super(pl, 3, "<gradient:#ff5555:#5555ff><bold>ColorWar</bold></gradient>");
        }

        @Override
        protected void build(Player p) {
            set(10, icon(Material.LIME_CONCRETE, "<green><bold>Jugar", "<gray>Elige una arena y únete."),
                    e -> new Arenas(plugin).open(p));
            set(12, icon(Material.BOOK, "<aqua><bold>Mis estadísticas", "<gray>Victorias, kills y monedas."),
                    e -> new Stats(plugin).open(p));
            set(14, icon(Material.NETHER_STAR, "<gold><bold>Clasificación", "<gray>Los mejores jugadores."),
                    e -> new Top(plugin, StatsManager.Category.WINS).open(p));
            set(16, icon(Material.CHEST, "<yellow><bold>Recompensas", "<gray>Premios por partida."),
                    e -> new Rewards(plugin).open(p));
            if (plugin.games().gameOf(p.getUniqueId()) != null) {
                set(22, icon(Material.BARRIER, "<red><bold>Salir de la partida"), e -> {
                    p.closeInventory();
                    plugin.games().leave(p);
                });
            }
            if (p.hasPermission("colorwar.admin")) {
                set(26, icon(Material.COMMAND_BLOCK, "<light_purple><bold>Administrar arenas"),
                        e -> new Admin(plugin).open(p));
            }
        }
    }

    // --------------------------------------------------------------- arenas
    public static class Arenas extends Menu {
        public Arenas(ColorWarPlugin pl) {
            super(pl, 6, "<dark_gray>Arenas");
        }

        @Override
        protected void build(Player p) {
            int slot = 0;
            for (Arena a : plugin.arenas().all()) {
                if (slot > 44) break;
                Game g = plugin.games().gameOf(a);
                Material mat;
                String status;
                if (!a.isReady()) {
                    mat = Material.GRAY_CONCRETE;
                    status = "<gray>No disponible";
                } else if (g == null || g.state == Game.State.WAITING) {
                    mat = Material.LIME_CONCRETE;
                    status = "<green>Esperando jugadores";
                } else if (g.state == Game.State.COUNTDOWN) {
                    mat = Material.YELLOW_CONCRETE;
                    status = "<yellow>Empezando...";
                } else {
                    mat = Material.RED_CONCRETE;
                    status = "<red>En juego";
                }
                int count = g == null ? 0 : g.playerCount();
                set(slot++, icon(mat, "<white><bold>" + a.name,
                        "<gray>Estado: " + status,
                        "<gray>Modo: <white>" + (a.mode == Arena.PlayMode.SOLO ? "Solo" : "Equipos (" + a.teams + ")"),
                        "<gray>Jugadores: <white>" + count + "/" + Math.min(a.maxPlayers, Colors.PALETTE.size()),
                        "<gray>Duración: <white>" + a.duration + "s",
                        "",
                        "<yellow>Click para unirte"), e -> {
                    p.closeInventory();
                    String err = plugin.games().join(p, a);
                    if (err != null) Msg.send(p, "<red>" + err);
                });
            }
            set(49, icon(Material.ARROW, "<gray>Volver"), e -> new Main(plugin).open(p));
        }
    }

    // ----------------------------------------------------------- estadísticas
    public static class Stats extends Menu {
        public Stats(ColorWarPlugin pl) {
            super(pl, 3, "<dark_gray>Mis estadísticas");
        }

        @Override
        protected void build(Player p) {
            StatsManager.PlayerStats s = plugin.stats().get(p.getUniqueId(), p.getName());
            double rate = s.games == 0 ? 0 : s.wins * 100.0 / s.games;
            set(10, icon(Material.GOLD_INGOT, "<gold>Victorias", "<white>" + s.wins), null);
            set(11, icon(Material.CLOCK, "<yellow>Partidas", "<white>" + s.games,
                    "<gray>Win rate: <white>" + String.format(Locale.US, "%.1f%%", rate)), null);
            set(12, icon(Material.DIAMOND_SWORD, "<red>Eliminaciones", "<white>" + s.kills), null);
            set(14, icon(Material.SKELETON_SKULL, "<gray>Muertes", "<white>" + s.deaths), null);
            set(15, icon(Material.FILLED_MAP, "<green>Mejor % de mapa",
                    "<white>" + String.format(Locale.US, "%.1f%%", s.best)), null);
            set(16, icon(Material.EMERALD, "<green>Monedas", "<white>" + s.coins), null);
            set(22, icon(Material.ARROW, "<gray>Volver"), e -> new Main(plugin).open(p));
        }
    }

    // ----------------------------------------------------------- clasificación
    public static class Top extends Menu {
        private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        private final StatsManager.Category cat;

        public Top(ColorWarPlugin pl, StatsManager.Category cat) {
            super(pl, 5, "<dark_gray>Clasificación: " + cat.label);
            this.cat = cat;
        }

        @Override
        protected void build(Player p) {
            int cs = 1;
            for (StatsManager.Category c : StatsManager.Category.values()) {
                set(cs, icon(c.icon, (c == cat ? "<yellow><bold>" : "<white>") + c.label,
                        c == cat ? "<green>Seleccionado" : "<gray>Click para ver"),
                        e -> new Top(plugin, c).open(p));
                cs += 2;
            }
            List<StatsManager.PlayerStats> top = plugin.stats().top(cat, SLOTS.length);
            for (int i = 0; i < top.size(); i++) {
                StatsManager.PlayerStats s = top.get(i);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(s.id));
                sm.displayName(Msg.item("<gold>#" + (i + 1) + " <white><n>", Msg.p("n", s.name)));
                String val = cat == StatsManager.Category.BEST
                        ? String.format(Locale.US, "%.1f%%", s.value(cat))
                        : String.valueOf((int) s.value(cat));
                sm.lore(List.of(Msg.item("<gray>" + cat.label + ": <white>" + val)));
                head.setItemMeta(sm);
                set(SLOTS[i], head, null);
            }
            if (top.isEmpty()) {
                set(22, icon(Material.PAPER, "<gray>Todavía no hay datos"), null);
            }
            set(40, icon(Material.ARROW, "<gray>Volver"), e -> new Main(plugin).open(p));
        }
    }

    // ------------------------------------------------------------ recompensas
    public static class Rewards extends Menu {
        public Rewards(ColorWarPlugin pl) {
            super(pl, 3, "<dark_gray>Recompensas");
        }

        private String coinsLine(String path) {
            FileConfiguration c = plugin.getConfig();
            int coins = c.getInt(path + ".coins", 0);
            return "<gray>Monedas: <gold>+" + coins;
        }

        private ItemStack reward(Material m, String name, String path) {
            FileConfiguration c = plugin.getConfig();
            boolean extra = !c.getStringList(path + ".commands").isEmpty();
            if (extra) return icon(m, name, coinsLine(path), "<light_purple>+ Premio especial");
            return icon(m, name, coinsLine(path));
        }

        @Override
        protected void build(Player p) {
            set(10, reward(Material.PAPER, "<white>Participación", "rewards.participation"), null);
            set(12, reward(Material.GOLD_BLOCK, "<gold>1.er puesto", "rewards.placements.1"), null);
            set(13, reward(Material.IRON_BLOCK, "<gray>2.º puesto", "rewards.placements.2"), null);
            set(14, reward(Material.COPPER_BLOCK, "<#b87333>3.er puesto", "rewards.placements.3"), null);
            set(16, reward(Material.DIAMOND_SWORD, "<red>Por eliminación", "rewards.per-kill"), null);
            int coins = plugin.stats().get(p.getUniqueId(), p.getName()).coins;
            set(4, icon(Material.EMERALD, "<green>Tus monedas", "<white>" + coins), null);
            set(22, icon(Material.ARROW, "<gray>Volver"), e -> new Main(plugin).open(p));
        }
    }

    // ----------------------------------------------------------------- admin
    public static class Admin extends Menu {
        public Admin(ColorWarPlugin pl) {
            super(pl, 6, "<dark_gray>Administrar arenas");
        }

        @Override
        protected void build(Player p) {
            int slot = 0;
            for (Arena a : plugin.arenas().all()) {
                if (slot > 44) break;
                String problem = a.problem();
                set(slot++, icon(problem == null ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                        "<white><bold>" + a.name,
                        problem == null ? "<green>Lista" : "<red>" + problem,
                        "<gray>Click para configurar"), e -> new ArenaAdmin(plugin, a).open(p));
            }
            set(45, icon(Material.WOODEN_AXE, "<gold>Hacha de selección",
                    "<gray>Clic izq = pos1, clic der = pos2",
                    "<gray>Luego: /cw create [nombre] [solo|team]"), e -> {
                p.getInventory().addItem(Selection.wand());
                Msg.send(p, "<green>Hacha entregada.");
            });
            set(49, icon(Material.ARROW, "<gray>Volver"), e -> new Main(plugin).open(p));
        }
    }

    public static class ArenaAdmin extends Menu {
        private final Arena a;

        public ArenaAdmin(ColorWarPlugin pl, Arena a) {
            super(pl, 4, "<dark_gray>Arena: " + a.name);
            this.a = a;
        }

        private void changed(Player p) {
            plugin.arenas().save();
            refresh(p);
        }

        @Override
        protected void build(Player p) {
            set(10, icon(Material.DIAMOND_SWORD, "<yellow>Modo: <white>" + a.mode, "<gray>Click para cambiar"), e -> {
                a.mode = a.mode == Arena.PlayMode.SOLO ? Arena.PlayMode.TEAM : Arena.PlayMode.SOLO;
                changed(p);
            });
            set(11, icon(Material.ENDER_PEARL, "<yellow>Spawns: <white>" + a.spawnMode,
                    "<gray>Definidos: <white>" + a.spawns.size(), "<gray>Click para cambiar"), e -> {
                a.spawnMode = a.spawnMode == Arena.SpawnMode.RANDOM ? Arena.SpawnMode.DEFINED : Arena.SpawnMode.RANDOM;
                changed(p);
            });
            set(12, icon(Material.PLAYER_HEAD, "<yellow>Mínimo: <white>" + a.minPlayers,
                    "<gray>Izq +1 | Der -1"), e -> {
                a.minPlayers = Math.max(1, Math.min(a.maxPlayers, a.minPlayers + (e.isLeftClick() ? 1 : -1)));
                changed(p);
            });
            set(13, icon(Material.PLAYER_HEAD, "<yellow>Máximo: <white>" + a.maxPlayers,
                    "<gray>Izq +1 | Der -1"), e -> {
                a.maxPlayers = Math.max(a.minPlayers, Math.min(Colors.PALETTE.size(), a.maxPlayers + (e.isLeftClick() ? 1 : -1)));
                changed(p);
            });
            set(14, icon(Material.CLOCK, "<yellow>Duración: <white>" + a.duration + "s",
                    "<gray>Izq +30s | Der -30s"), e -> {
                a.duration = Math.max(30, Math.min(1800, a.duration + (e.isLeftClick() ? 30 : -30)));
                changed(p);
            });
            set(15, icon(Material.WHITE_BANNER, "<yellow>Equipos: <white>" + a.teams,
                    "<gray>Izq +1 | Der -1"), e -> {
                a.teams = Math.max(2, Math.min(8, a.teams + (e.isLeftClick() ? 1 : -1)));
                changed(p);
            });
            set(16, icon(a.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    "<yellow>Activa: <white>" + a.enabled, "<gray>Click para cambiar"), e -> {
                a.enabled = !a.enabled;
                changed(p);
            });
            set(19, icon(Material.COMPASS, "<aqua>Teletransportarme"), e -> {
                if (a.problem() == null) {
                    p.closeInventory();
                    p.teleport(a.centerTop());
                }
            });
            set(20, icon(Material.TNT, "<red>Borrar spawns"), e -> {
                a.spawns.clear();
                changed(p);
            });
            set(21, icon(Material.BRICKS, "<gold>Reconstruir suelo y bordes", "<gray>Repinta el suelo y el borde"), e -> {
                if (plugin.games().gameOf(a) != null) {
                    Msg.send(p, "<red>La arena está en uso.");
                    return;
                }
                Msg.send(p, "<gray>Reconstruyendo...");
                ArenaBuilder.build(plugin, a, () -> {
                    if (p.isOnline()) Msg.send(p, "<green>Suelo y bordes listos.");
                });
            });
            set(22, icon(Material.REDSTONE_TORCH, "<red>Probar en solitario (debug)",
                    "<gray>Partida de prueba con bots,", "<gray>sin estadísticas ni premios."), e -> {
                p.closeInventory();
                String err = plugin.games().joinDebug(p, a, plugin.getConfig().getInt("debug.default-bots", 2), null);
                if (err != null) Msg.send(p, "<red>" + err);
            });
            set(25, icon(Material.BARRIER, "<dark_red><bold>Eliminar arena", "<gray>Shift + click para confirmar"), e -> {
                if (!e.isShiftClick()) return;
                plugin.deleteArena(a);
                new Admin(plugin).open(p);
            });
            set(31, icon(Material.ARROW, "<gray>Volver"), e -> new Admin(plugin).open(p));
        }
    }
}
