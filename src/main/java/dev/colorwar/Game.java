package dev.colorwar;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Una partida de ColorWar en una arena.
 * Mecánica tipo Paper.io: sales de tu territorio dejando una estela; al volver
 * cierras el bucle y reclamas todo lo encerrado. Si alguien pisa tu estela, mueres.
 */
public class Game {
    public enum State { WAITING, COUNTDOWN, RUNNING, ENDING }

    /** Bot a crear: nombre y nivel de inteligencia (1-3). */
    public record BotSpec(String name, int level) {}

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private final ColorWarPlugin plugin;
    public final Arena arena;
    private final World world;
    public State state = State.WAITING;

    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();
    private final int w, d, minX, minZ, floorY;
    private int[][] owner, trail;               // owner: lado+1 | trail: slot del jugador
    private final ArrayDeque<Integer> dirty = new ArrayDeque<>();

    private BukkitTask countdownTask, renderTask, clockTask, finishTask, celebrationTask, spreadTask;
    private final List<GamePlayer> diedLeft = new ArrayList<>();   // eliminados que ya salieron (cobran premio al final)
    private int countdown, timeLeft, elapsed;
    private boolean floorTouched, closing;

    private int sideCount;
    private Colors.Entry[] sideColor;
    private String[] sideName;
    private boolean[] sideOut;
    private long[] sideOutAt;
    private GamePlayer[] bySlot;
    private BossBar bar;
    private final Random rnd = new Random();
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private boolean debug;
    private boolean hasBots;   // partida normal rellenada con bots
    private BukkitTask botTask;

    public Game(ColorWarPlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.world = Bukkit.getWorld(arena.world);
        this.minX = arena.minX();
        this.minZ = arena.minZ();
        this.floorY = arena.floorY;
        this.w = arena.width();
        this.d = arena.depth();
        this.owner = new int[w][d];
        this.trail = new int[w][d];
    }

    // ------------------------------------------------------------------ info

    public int playerCount() { return players.size(); }
    public int maxPlayers() { return Math.min(arena.maxPlayers, Colors.PALETTE.size()); }
    public boolean has(UUID id) { return players.containsKey(id); }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean b) { debug = b; }

    private int humanCount() {
        int n = 0;
        for (GamePlayer gp : players.values()) if (!gp.bot) n++;
        return n;
    }

    private Location waitingLocation() {
        if (arena.lobby != null && arena.lobby.getWorld() != null) return arena.lobby;
        return arena.centerTop();
    }

    private Location cellLocation(int c, float yaw) {
        return new Location(world, minX + c / d + 0.5, floorY + 1.0, minZ + c % d + 0.5, yaw, 0f);
    }

    private float yawToCenter(int gx, int gz) {
        double dx = (w / 2.0) - gx, dz = (d / 2.0) - gz;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private void broadcast(String mm, TagResolver... r) {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) Msg.send(p, mm, r);
        }
    }

    private static String pct(int cells, int total) {
        return String.format(Locale.US, "%.1f%%", cells * 100.0 / total);
    }

    /** Por defecto las partidas no tienen límite de tiempo: terminan al quedar un ganador o completarse el mapa. */
    private boolean timeLimited() {
        return plugin.getConfig().getBoolean("time-limit", false) && arena.duration > 0;
    }

    private String timeText() {
        return fmtTime(timeLimited() ? Math.max(0, timeLeft) : elapsed);
    }

    private static String fmtTime(int s) {
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------ jugadores

    public void addPlayer(Player p) {
        if (debug && state == State.RUNNING) {
            joinMidGame(p);
            return;
        }
        GamePlayer gp = new GamePlayer(p);
        gp.savedContents = p.getInventory().getContents();
        gp.savedArmor = p.getInventory().getArmorContents();
        gp.savedMode = p.getGameMode();
        gp.savedLoc = p.getLocation();
        gp.savedFood = p.getFoodLevel();
        gp.alive = true;
        players.put(p.getUniqueId(), gp);
        plugin.games().register(p.getUniqueId(), this);

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.ADVENTURE);
        p.setFoodLevel(20);
        p.getInventory().setItem(8, leaveItem());
        p.teleport(waitingLocation());

        broadcast("<white><n> <gray>se unió <dark_gray>(<white>" + players.size() + "<gray>/<white>" + maxPlayers() + "<dark_gray>)",
                Msg.p("n", p.getName()));
        if (debug) return;
        if (state == State.WAITING && canStartCountdown()) {
            startCountdown();
        } else if (state == State.COUNTDOWN && humanCount() >= maxPlayers() && countdown > 5) {
            countdown = 5;
        }
    }

    public void removePlayer(Player p) {
        removePlayer(p, false);
    }

    public void removePlayer(Player p, boolean silent) {
        GamePlayer gp = players.get(p.getUniqueId());
        if (gp == null) return;
        gp.left = true;
        if (state == State.RUNNING) {
            clearTrail(gp);
            gp.alive = false;
            markOut(gp);
            if (arena.mode == Arena.PlayMode.SOLO && gp.side >= 0) clearSide(gp.side);
        }
        players.remove(p.getUniqueId());
        plugin.games().unregister(p.getUniqueId());
        restorePlayer(gp, p);
        if (!silent) broadcast("<white><n> <gray>salió de la partida.", Msg.p("n", gp.name));

        if (state == State.COUNTDOWN && !canStartCountdown()) {
            if (countdownTask != null) {
                countdownTask.cancel();
                countdownTask = null;
            }
            state = State.WAITING;
            broadcast("<red>Cuenta atrás cancelada: faltan jugadores.");
        }
        if (humanCount() == 0 && !closing) {
            stopNow();
            return;
        }
        if (state == State.RUNNING) checkEnd();
    }

    private ItemStack leaveItem() {
        ItemStack it = new ItemStack(Material.RED_DYE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Msg.item("<red>Salir de la partida"));
        it.setItemMeta(m);
        return it;
    }

    private void restorePlayer(GamePlayer gp, Player p) {
        if (p == null) return;
        if (bar != null) p.hideBossBar(bar);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
        p.getInventory().clear();
        if (gp.savedContents != null) p.getInventory().setContents(gp.savedContents);
        if (gp.savedArmor != null) p.getInventory().setArmorContents(gp.savedArmor);
        p.setGameMode(gp.savedMode != null ? gp.savedMode : GameMode.SURVIVAL);
        p.setFoodLevel(gp.savedFood);
        Location lobby = plugin.mainLobby();
        Location target = lobby != null ? lobby : gp.savedLoc;
        if (target != null) p.teleport(target);
    }

    // --------------------------------------------------------- cuenta atrás

    private void startCountdown() {
        state = State.COUNTDOWN;
        countdown = botsFill() ? plugin.getConfig().getInt("bots.wait-seconds", 30)
                : plugin.getConfig().getInt("countdown-seconds", 15);
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!canStartCountdown()) {
                countdownTask.cancel();
                countdownTask = null;
                state = State.WAITING;
                broadcast("<red>Cuenta atrás cancelada: faltan jugadores.");
                return;
            }
            if (countdown <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                fillBots();
                start();
                return;
            }
            if (countdown <= 5 || countdown % 5 == 0) {
                for (GamePlayer gp : players.values()) {
                    Player p = gp.player();
                    if (p == null) continue;
                    p.sendActionBar(Msg.parse("<yellow>La partida empieza en <white>" + countdown + "s"
                            + (botsFill() && players.size() < maxPlayers() ? " <gray>· los puestos libres se rellenan con bots" : "")));
                    if (countdown <= 5) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                }
            }
            countdown--;
        }, 0L, 20L);
    }

    public boolean forceStart() {
        if ((state != State.WAITING && state != State.COUNTDOWN) || players.isEmpty()) return false;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        start();
        return true;
    }

    // --------------------------------------------------------------- inicio

    private void start() {
        state = State.RUNNING;
        floorTouched = true;
        timeLeft = arena.duration;
        elapsed = 0;
        owner = new int[w][d];
        trail = new int[w][d];
        dirty.clear();

        List<GamePlayer> list = new ArrayList<>();
        for (GamePlayer gp : players.values()) if (!gp.spectator) list.add(gp);
        Collections.shuffle(list, rnd);
        int n = list.size();
        boolean team = arena.mode == Arena.PlayMode.TEAM;
        sideCount = team ? Math.max(1, Math.min(Math.min(arena.teams, n), Colors.PALETTE.size())) : n;
        sideColor = new Colors.Entry[sideCount];
        sideName = new String[sideCount];
        sideOut = new boolean[sideCount];
        sideOutAt = new long[sideCount];
        bySlot = new GamePlayer[n + 1];

        for (int i = 0; i < n; i++) {
            GamePlayer gp = list.get(i);
            gp.side = team ? i % sideCount : i;
            gp.slot = i + 1;
            bySlot[gp.slot] = gp;
            gp.alive = true;
            gp.eliminated = false;
            gp.kills = 0;
            gp.deaths = 0;
            gp.peak = 0;
            gp.trailCells.clear();
            if (!team) sideName[gp.side] = gp.name;
        }
        for (int s = 0; s < sideCount; s++) {
            sideColor[s] = Colors.PALETTE.get(s);
            if (team) sideName[s] = "Equipo " + sideColor[s].name();
        }

        List<int[]> starts = chooseStarts(sideCount);
        int radius = plugin.getConfig().getInt("start-radius", 2);
        for (int s = 0; s < sideCount; s++) claimSquare(s, starts.get(s)[0], starts.get(s)[1], radius);
        for (int c = 0; c < w * d; c++) dirty.add(c);

        bar = BossBar.bossBar(Msg.parse("<gold>ColorWar"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (GamePlayer sp : players.values()) {
            Player spp = sp.spectator ? sp.player() : null;
            if (spp != null) spp.showBossBar(bar);
        }
        int speed = plugin.getConfig().getInt("speed-level", 1);

        for (GamePlayer gp : list) {
            Player p = gp.player();
            if (p == null && !gp.bot) continue;
            int[] st = starts.get(gp.side);
            gp.lastCell = st[0] * d + st[1];
            gp.homeX = st[0];
            gp.homeZ = st[1];
            if (gp.bot) {
                initBot(gp);
                spawnBotEntity(gp);
                continue;
            }
            p.teleport(cellLocation(gp.lastCell, yawToCenter(st[0], st[1])));
            p.setGameMode(GameMode.ADVENTURE);
            if (speed > 0) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION,
                        speed - 1, false, false, false));
            }
            gp.board = Bukkit.getScoreboardManager().getNewScoreboard();
            gp.obj = gp.board.registerNewObjective("cw", Criteria.DUMMY, Msg.parse("<gold><bold>ColorWar"));
            gp.obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            p.setScoreboard(gp.board);
            p.showBossBar(bar);
            Colors.Entry ce = sideColor[gp.side];
            p.showTitle(Title.title(Msg.parse("<gold><bold>¡A conquistar!"),
                    Msg.parse("<gray>Tu color: <" + ce.mm() + ">" + ce.name())));
            tipThirdPerson(p);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        renderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 1L, 1L);
        clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
        if (hasBotPlayers()) {
            long delay = Math.max(1, plugin.getConfig().getInt("bots.delay-ticks",
                    plugin.getConfig().getInt("debug.bot-delay-ticks", 3)));
            botTask = Bukkit.getScheduler().runTaskTimer(plugin, this::botTick, 20L, delay);
        }
    }

    private List<int[]> chooseStarts(int n) {
        List<int[]> res = new ArrayList<>();
        int radius = plugin.getConfig().getInt("start-radius", 2);
        int margin = Math.max(1, Math.min(radius + 2, Math.min(w, d) / 2 - 1));

        if (arena.spawnMode == Arena.SpawnMode.DEFINED && !arena.spawns.isEmpty()) {
            List<Location> sp = new ArrayList<>(arena.spawns);
            Collections.shuffle(sp, rnd);
            for (Location l : sp) {
                if (res.size() >= n) break;
                int gx = clamp(l.getBlockX() - minX, margin, w - 1 - margin);
                int gz = clamp(l.getBlockZ() - minZ, margin, d - 1 - margin);
                res.add(new int[]{gx, gz});
            }
        }
        int minDist = Math.max(4, Math.min(w, d) / (n + 1));
        int tries = 0;
        while (res.size() < n) {
            int gx = margin + rnd.nextInt(Math.max(1, w - 2 * margin));
            int gz = margin + rnd.nextInt(Math.max(1, d - 2 * margin));
            boolean ok = true;
            for (int[] o : res) {
                if (Math.max(Math.abs(o[0] - gx), Math.abs(o[1] - gz)) < minDist) {
                    ok = false;
                    break;
                }
            }
            if (ok || ++tries > 300) res.add(new int[]{gx, gz});
        }
        return res;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void claimSquare(int side, int cx, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int gx = cx + dx, gz = cz + dz;
                if (gx < 0 || gz < 0 || gx >= w || gz >= d) continue;
                owner[gx][gz] = side + 1;
                dirty.add(gx * d + gz);
            }
        }
    }

    // ------------------------------------------------------------ movimiento

    /** @return true si hay que cancelar el movimiento (fuera de la arena). */
    public boolean handleMove(Player p, Location to) {
        GamePlayer gp = players.get(p.getUniqueId());
        if (gp == null || !gp.alive) return false;
        int gx = to.getBlockX() - minX, gz = to.getBlockZ() - minZ;
        if (gx < 0 || gz < 0 || gx >= w || gz >= d) return true;
        if (to.getY() < floorY - 1) {
            Location back = gp.lastCell >= 0 ? cellLocation(gp.lastCell, p.getLocation().getYaw()) : waitingLocation();
            p.teleport(back);
            return false;
        }
        if (state != State.RUNNING) return false;
        int c = gx * d + gz;
        if (c == gp.lastCell) return false;
        processStep(gp, gx, gz, c);
        return false;
    }

    private void processStep(GamePlayer gp, int gx, int gz, int c) {
        gp.lastCell = c;
        int t = trail[gx][gz];
        if (t > 0) {
            if (t == gp.slot) {
                // Pisar tu propia estela es inofensivo (con own-trail-kills: true vuelve a matar)
                if (plugin.getConfig().getBoolean("own-trail-kills", false)) {
                    int idx = gp.trailCells.lastIndexOf(c);
                    if (idx < gp.trailCells.size() - 2) kill(gp, null);   // tolerancia al pisar la celda anterior
                }
                return;
            }
            GamePlayer victim = bySlot[t];
            boolean safe = arena.mode == Arena.PlayMode.TEAM && victim != null && victim.side == gp.side
                    && plugin.getConfig().getBoolean("friendly-trail-safe", true);
            if (safe) {
                // Equipos: al tocar la estela de un compañero ambas se fusionan y se captura todo el conjunto
                if (!gp.trailCells.isEmpty() && victim.alive && !victim.trailCells.isEmpty()
                        && plugin.getConfig().getBoolean("team-merge-trails", true)) {
                    captureGroup(gp, victim);
                }
                return;
            }
            if (victim != null) kill(victim, gp);
            else trail[gx][gz] = 0;
        }
        if (!gp.alive) return;
        if (owner[gx][gz] == gp.side + 1) {
            if (!gp.trailCells.isEmpty()) capture(gp);
        } else {
            trail[gx][gz] = gp.slot;
            gp.trailCells.add(c);
            dirty.add(c);
        }
    }

    // ------------------------------------------------------------- captura

    private void capture(GamePlayer gp) {
        captureGroup(gp, null);
    }

    /**
     * Convierte en territorio la estela de gp (y la de su compañero si se fusionaron) y rellena todo lo
     * que quede encerrado entre esas estelas y el territorio del lado.
     */
    private void captureGroup(GamePlayer gp, GamePlayer mate) {
        int id = gp.side + 1;
        int gained = 0;
        GamePlayer[] group = mate == null ? new GamePlayer[]{gp} : new GamePlayer[]{gp, mate};
        for (GamePlayer g : group) {
            gained += g.trailCells.size();
            for (int c : g.trailCells) {
                int gx = c / d, gz = c % d;
                trail[gx][gz] = 0;
                owner[gx][gz] = id;
                dirty.add(c);
            }
            g.trailCells.clear();
        }

        boolean[][] out = new boolean[w][d];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int gx = 0; gx < w; gx++) {
            seed(out, q, gx, 0, id);
            seed(out, q, gx, d - 1, id);
        }
        for (int gz = 0; gz < d; gz++) {
            seed(out, q, 0, gz, id);
            seed(out, q, w - 1, gz, id);
        }
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            for (int k = 0; k < 4; k++) {
                int nx = cur[0] + DX[k], nz = cur[1] + DZ[k];
                if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                if (!out[nx][nz] && owner[nx][nz] != id) {
                    out[nx][nz] = true;
                    q.add(new int[]{nx, nz});
                }
            }
        }
        for (int gx = 0; gx < w; gx++) {
            for (int gz = 0; gz < d; gz++) {
                if (!out[gx][gz] && owner[gx][gz] != id) {
                    owner[gx][gz] = id;
                    gained++;
                    dirty.add(gx * d + gz);
                }
            }
        }
        String gainText = pct(gained, w * d);
        for (GamePlayer g : group) {
            Player p = g.player();
            if (p == null) continue;
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            p.sendActionBar(Msg.parse("<green>+" + gainText + " <gray>de territorio"
                    + (mate != null ? " <aqua>(estelas fusionadas)" : "")));
        }
        checkFullMap(counts());
    }

    /** Si algún lado completó el mapa (win-percent), gana al instante. */
    private boolean checkFullMap(int[] counts) {
        if (state != State.RUNNING) return false;
        int total = w * d, best = -1, bestSide = -1;
        for (int s = 0; s < sideCount; s++) {
            if (counts[s] > best) {
                best = counts[s];
                bestSide = s;
            }
        }
        if (bestSide >= 0 && best * 100.0 / total >= plugin.getConfig().getDouble("win-percent", 100.0)) {
            broadcast("<gold><bold>¡<n> completó el mapa!", Msg.p("n", sideName[bestSide]));
            end();
            return true;
        }
        return false;
    }

    private void seed(boolean[][] out, ArrayDeque<int[]> q, int x, int z, int id) {
        if (!out[x][z] && owner[x][z] != id) {
            out[x][z] = true;
            q.add(new int[]{x, z});
        }
    }

    private void clearTrail(GamePlayer gp) {
        for (int c : gp.trailCells) {
            trail[c / d][c % d] = 0;
            dirty.add(c);
        }
        gp.trailCells.clear();
    }

    private void clearSide(int side) {
        for (int gx = 0; gx < w; gx++) {
            for (int gz = 0; gz < d; gz++) {
                if (owner[gx][gz] == side + 1) {
                    owner[gx][gz] = 0;
                    dirty.add(gx * d + gz);
                }
            }
        }
    }

    // --------------------------------------------------------------- muerte

    public void kill(GamePlayer v, GamePlayer killer) {
        if (state != State.RUNNING || !v.alive) return;
        v.alive = false;
        v.deaths++;
        clearTrail(v);
        Player vp = v.player();
        if (v.bot && killer != null && killer != v) v.revenge = killer;
        for (GamePlayer o : players.values()) {   // los bots del mismo equipo se ponen en alerta
            if (o.bot && o != v && o.side == v.side && o.alive) o.alert = 40;
        }

        if (killer != null && killer != v) {
            killer.kills++;
            Player kp = killer.player();
            if (kp != null) {
                kp.playSound(kp.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                if (!debug) plugin.rewards().giveKill(kp);
            }
            broadcast("<white><k> <gray>eliminó a <white><v>", Msg.p("k", killer.name), Msg.p("v", v.name));
        } else {
            broadcast("<white><v> <gray>chocó con su propia estela.", Msg.p("v", v.name));
        }

        if (v.bot) {
            if (v.entity != null && v.entity.isValid()) {
                world.spawnParticle(Particle.CLOUD, v.entity.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.03);
            }
            removeBotEntity(v);
            boolean revive = debug || (arena.mode == Arena.PlayMode.TEAM && plugin.getConfig().getBoolean("team-respawn", false));
            if (revive) {   // debug: reaparecen para seguir probando
                if (arena.mode == Arena.PlayMode.SOLO) clearSide(v.side);
                Bukkit.getScheduler().runTaskLater(plugin, () -> botRespawn(v), 60L);
            } else {        // partida normal: queda eliminado como un jugador
                v.eliminated = true;
                markOut(v);
                if (arena.mode == Arena.PlayMode.SOLO) clearSide(v.side);
                checkEnd();
            }
            return;
        }
        boolean respawn = arena.mode == Arena.PlayMode.TEAM && plugin.getConfig().getBoolean("team-respawn", false);
        if (respawn) {
            int secs = plugin.getConfig().getInt("team-respawn-seconds", 5);
            if (vp != null) {
                vp.setGameMode(GameMode.SPECTATOR);
                vp.showTitle(Title.title(Msg.parse("<red><bold>¡Eliminado!"),
                        Msg.parse("<gray>Reapareces en " + secs + "s")));
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> respawn(v), secs * 20L);
        } else {
            v.eliminated = true;
            markOut(v);
            if (arena.mode == Arena.PlayMode.SOLO) clearSide(v.side);
            int secs = plugin.getConfig().getInt("death-spectate-seconds", 4);
            if (vp != null) {
                vp.setGameMode(GameMode.SPECTATOR);
                vp.showTitle(Title.title(Msg.parse("<red><bold>¡Eliminado!"),
                        Msg.parse("<gray>Saldrás de la partida en " + secs + "s")));
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> leaveAfterDeath(v), secs * 20L);
        }
        checkEnd();
    }

    /** Pasados unos segundos de espectador, el eliminado sale de la partida. */
    private void leaveAfterDeath(GamePlayer gp) {
        if (state != State.RUNNING || gp.left || !players.containsKey(gp.id)) return;
        Player p = gp.player();
        if (p == null) return;
        diedLeft.add(gp);
        Msg.send(p, "<gray>Fuiste eliminado. ¡Gracias por jugar!");
        removePlayer(p, true);
    }

    private void respawn(GamePlayer gp) {
        if (state != State.RUNNING || gp.left || gp.eliminated || !players.containsKey(gp.id)) return;
        Player p = gp.player();
        if (p == null) return;
        int[] cell = randomOwnedCell(gp.side);
        if (cell == null) {
            cell = chooseStarts(1).get(0);
            claimSquare(gp.side, cell[0], cell[1], 1);
        }
        gp.alive = true;
        gp.lastCell = cell[0] * d + cell[1];
        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(cellLocation(gp.lastCell, yawToCenter(cell[0], cell[1])));
    }

    private int[] randomOwnedCell(int side) {
        List<int[]> cells = new ArrayList<>();
        for (int gx = 0; gx < w; gx++) {
            for (int gz = 0; gz < d; gz++) {
                if (owner[gx][gz] == side + 1) cells.add(new int[]{gx, gz});
            }
        }
        return cells.isEmpty() ? null : cells.get(rnd.nextInt(cells.size()));
    }

    /** Marca el lado como fuera de juego si ya no le queda ningún jugador activo. */
    private void markOut(GamePlayer gp) {
        if (gp.side < 0 || sideOut == null) return;
        for (GamePlayer o : players.values()) {
            if (o.side == gp.side && !o.left && !o.eliminated) return;
        }
        sideOut[gp.side] = true;
        sideOutAt[gp.side] = System.currentTimeMillis();
    }

    private void checkEnd() {
        if (debug || state != State.RUNNING || sideCount < 2) return;
        Set<Integer> active = new HashSet<>();
        boolean humanActive = false;
        for (GamePlayer gp : players.values()) {
            if (gp.spectator || gp.left || gp.eliminated) continue;
            active.add(gp.side);
            if (!gp.bot) humanActive = true;
        }
        // Sin humanos activos no tiene sentido seguir: los bots no se juegan solos en partidas normales
        if (active.size() <= 1 || (hasBots && !humanActive)) end();
    }

    // ----------------------------------------------- bots de relleno y debug
    /** La cámara es cosa del cliente: el servidor solo puede sugerir cambiarla (F5). */
    private void tipThirdPerson(Player p) {
        if (!plugin.getConfig().getBoolean("tips.third-person", true)) return;
        Msg.send(p, "<yellow>Consejo: <gray>pulsa <white>F5</white> para jugar en tercera persona y ver mejor tu estela. "
                + "Puedes cambiarlo cuando quieras.");
    }

    private boolean botsFill() {
        return !debug && plugin.getConfig().getBoolean("bots.enabled", true);
    }

    /** Con bots activados basta un humano para iniciar la espera; sin bots hace falta el mínimo de jugadores. */
    private boolean canStartCountdown() {
        return botsFill() ? humanCount() >= 1 : players.size() >= arena.minPlayers;
    }

    private boolean hasBotPlayers() {
        for (GamePlayer gp : players.values()) if (gp.bot) return true;
        return false;
    }

    private boolean nameTaken(String nm) {
        for (GamePlayer o : players.values()) if (o.name.equalsIgnoreCase(nm)) return true;
        return false;
    }

    /** Al terminar la espera, los puestos libres se rellenan con bots (una sola vez, antes de empezar). */
    private void fillBots() {
        if (!botsFill()) return;
        boolean toMin = "min".equalsIgnoreCase(plugin.getConfig().getString("bots.fill-to", "max"));
        int target = Math.min(maxPlayers(), toMin ? Math.max(arena.minPlayers, 2) : maxPlayers());
        int fixed = plugin.getConfig().getInt("bots.fill-level", 0);
        List<String> pool = new ArrayList<>(plugin.getConfig().getStringList("bots.names"));
        Collections.shuffle(pool, rnd);
        while (players.size() < target) {
            String name = null;
            for (String nm : pool) {
                if (!nameTaken(nm)) {
                    name = nm;
                    break;
                }
            }
            if (name == null) name = nextBotName();
            int lv = fixed >= 1 ? Math.min(3, fixed) : 1 + rnd.nextInt(3);
            addPreBot(name, lv);
            hasBots = true;
        }
        if (hasBots) broadcast("<gray>Se añadieron <white>" + (players.size() - humanCount()) + "<gray> bots para completar la partida.");
    }

    /** Debug: observar sin participar (útil con partidas solo de bots). */
    public void addSpectator(Player p) {
        GamePlayer gp = new GamePlayer(p);
        gp.savedContents = p.getInventory().getContents();
        gp.savedArmor = p.getInventory().getArmorContents();
        gp.savedMode = p.getGameMode();
        gp.savedLoc = p.getLocation();
        gp.savedFood = p.getFoodLevel();
        gp.spectator = true;
        gp.alive = false;
        players.put(p.getUniqueId(), gp);
        plugin.games().register(p.getUniqueId(), this);
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(arena.centerTop().add(0, 14, 0));
        if (bar != null) p.showBossBar(bar);
        Msg.send(p, "<gray>Estás observando la partida. Usa <white>/cw leave</white> para salir.");
    }

    /** Debug: un jugador entra con la partida ya en marcha (como un lado nuevo o en el equipo con menos miembros). */
    private void joinMidGame(Player p) {
        boolean team = arena.mode == Arena.PlayMode.TEAM;
        int side;
        if (team) {
            int[] members = new int[sideCount];
            for (GamePlayer o : players.values()) {
                if (o.side >= 0 && !o.left && !o.eliminated) members[o.side]++;
            }
            side = 0;
            for (int i = 1; i < sideCount; i++) if (members[i] < members[side]) side = i;
        } else {
            if (sideCount >= Colors.PALETTE.size()) {
                Msg.send(p, "<red>No caben más jugadores (máximo " + Colors.PALETTE.size() + " colores).");
                return;
            }
            side = sideCount++;
            sideColor = Arrays.copyOf(sideColor, sideCount);
            sideName = Arrays.copyOf(sideName, sideCount);
            sideOut = Arrays.copyOf(sideOut, sideCount);
            sideOutAt = Arrays.copyOf(sideOutAt, sideCount);
            sideColor[side] = Colors.PALETTE.get(side);
            sideName[side] = p.getName();
        }
        GamePlayer gp = new GamePlayer(p);
        gp.savedContents = p.getInventory().getContents();
        gp.savedArmor = p.getInventory().getArmorContents();
        gp.savedMode = p.getGameMode();
        gp.savedLoc = p.getLocation();
        gp.savedFood = p.getFoodLevel();
        gp.side = side;
        gp.alive = true;
        gp.slot = bySlot.length;
        bySlot = Arrays.copyOf(bySlot, bySlot.length + 1);
        bySlot[gp.slot] = gp;
        sideOut[side] = false;
        players.put(p.getUniqueId(), gp);
        plugin.games().register(p.getUniqueId(), this);

        int[] cell = team ? randomOwnedCell(side) : null;
        if (cell == null) {
            cell = chooseStarts(1).get(0);
            claimSquare(side, cell[0], cell[1], plugin.getConfig().getInt("start-radius", 2));
        }
        gp.lastCell = cell[0] * d + cell[1];
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.ADVENTURE);
        p.setFoodLevel(20);
        p.getInventory().setItem(8, leaveItem());
        p.teleport(cellLocation(gp.lastCell, yawToCenter(cell[0], cell[1])));
        int speed = plugin.getConfig().getInt("speed-level", 1);
        if (speed > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION,
                    speed - 1, false, false, false));
        }
        gp.board = Bukkit.getScoreboardManager().getNewScoreboard();
        gp.obj = gp.board.registerNewObjective("cw", Criteria.DUMMY, Msg.parse("<gold><bold>ColorWar"));
        gp.obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        p.setScoreboard(gp.board);
        if (bar != null) p.showBossBar(bar);
        tipThirdPerson(p);
        broadcast("<white><n> <gray>se unió a la partida de prueba.", Msg.p("n", p.getName()));
    }

    // ------------------------------------------------------------ debug

    public static String stars(int level) {
        return "★".repeat(Math.max(1, Math.min(3, level)));
    }

    public static String levelName(int level) {
        return switch (Math.max(1, Math.min(3, level))) {
            case 1 -> "Fácil";
            case 2 -> "Normal";
            default -> "Difícil";
        };
    }

    /** Antes de empezar: añade un bot por cada BotSpec, o n bots ("Bot 1"...) del nivel indicado. */
    public void addBots(int n, List<BotSpec> specs, int level) {
        if (specs != null && !specs.isEmpty()) {
            for (BotSpec sp : specs) {
                if (players.size() >= maxPlayers()) break;
                addPreBot(sp.name(), sp.level());
            }
            return;
        }
        for (int i = 0; i < n && players.size() < maxPlayers(); i++) addPreBot(nextBotName(), level);
    }

    private void addPreBot(String name, int level) {
        for (GamePlayer o : players.values()) if (o.name.equalsIgnoreCase(name)) return;
        GamePlayer b = new GamePlayer(name);
        b.level = Math.max(1, Math.min(3, level));
        b.alive = true;
        players.put(b.id, b);
    }

    private String nextBotName() {
        for (int i = 1; ; i++) {
            String nm = "Bot " + i;
            boolean used = false;
            for (GamePlayer o : players.values()) if (o.name.equalsIgnoreCase(nm)) used = true;
            if (!used) return nm;
        }
    }

    public List<String> botNames() {
        List<String> out = new ArrayList<>();
        for (GamePlayer o : players.values()) if (o.bot) out.add(o.name);
        return out;
    }

    /** Añade un bot con la partida en curso. teamNum: equipo 1..N (0 = automático, solo en modo equipos). */
    public String debugAddBot(String name, int teamNum, int level) {
        if (!debug) return "Esto solo funciona en una partida debug.";
        if (state != State.RUNNING) return "La partida no está en curso.";
        if (name == null || name.isBlank()) name = nextBotName();
        for (GamePlayer o : players.values()) {
            if (o.name.equalsIgnoreCase(name)) return "Ya hay un jugador llamado " + name + ".";
        }
        boolean team = arena.mode == Arena.PlayMode.TEAM;
        int side;
        if (team) {
            if (teamNum >= 1 && teamNum <= sideCount) {
                side = teamNum - 1;
            } else {
                int[] members = new int[sideCount];
                for (GamePlayer o : players.values()) {
                    if (o.side >= 0 && !o.left && !o.eliminated) members[o.side]++;
                }
                side = 0;
                for (int i = 1; i < sideCount; i++) if (members[i] < members[side]) side = i;
            }
        } else {
            if (sideCount >= Colors.PALETTE.size()) {
                return "No caben más jugadores (máximo " + Colors.PALETTE.size() + " colores).";
            }
            side = sideCount++;
            sideColor = Arrays.copyOf(sideColor, sideCount);
            sideName = Arrays.copyOf(sideName, sideCount);
            sideOut = Arrays.copyOf(sideOut, sideCount);
            sideOutAt = Arrays.copyOf(sideOutAt, sideCount);
            sideColor[side] = Colors.PALETTE.get(side);
            sideName[side] = name;
        }
        GamePlayer b = new GamePlayer(name);
        b.level = Math.max(1, Math.min(3, level));
        b.side = side;
        b.alive = true;
        b.slot = bySlot.length;
        bySlot = Arrays.copyOf(bySlot, bySlot.length + 1);
        bySlot[b.slot] = b;
        players.put(b.id, b);
        sideOut[side] = false;
        int[] cell = team ? randomOwnedCell(side) : null;
        if (cell == null) {
            cell = chooseStarts(1).get(0);
            claimSquare(side, cell[0], cell[1], plugin.getConfig().getInt("start-radius", 2));
        }
        b.lastCell = cell[0] * d + cell[1];
        b.homeX = cell[0];
        b.homeZ = cell[1];
        initBot(b);
        spawnBotEntity(b);
        broadcast("<white><n> <gray>(bot " + levelName(b.level) + ") se unió a la partida.", Msg.p("n", name));
        return null;
    }

    /** Cambia el nivel de un bot (o de todos con "todos"). */
    public String debugSetLevel(String target, int level) {
        boolean all = target.equalsIgnoreCase("todos") || target.equalsIgnoreCase("all");
        int lv = Math.max(1, Math.min(3, level)), n = 0;
        for (GamePlayer o : players.values()) {
            if (!o.bot || !(all || o.name.equalsIgnoreCase(target))) continue;
            o.level = lv;
            o.phase = 0;
            o.returning = false;
            if (o.entity != null && o.side >= 0) BotEntities.label(o.entity, o.name, botTag(o), sideColor[o.side]);
            n++;
        }
        return n == 0 ? "No hay ningún bot llamado " + target + "." : null;
    }

    public String debugRemoveBot(String name) {
        for (GamePlayer o : new ArrayList<>(players.values())) {
            if (o.bot && o.name.equalsIgnoreCase(name)) {
                o.left = true;
                o.alive = false;
                clearTrail(o);
                removeBotEntity(o);
                markOut(o);
                if (arena.mode == Arena.PlayMode.SOLO && o.side >= 0) clearSide(o.side);
                players.remove(o.id);
                broadcast("<white><n> <gray>(bot) fue retirado.", Msg.p("n", o.name));
                return null;
            }
        }
        return "No hay ningún bot llamado " + name + ".";
    }

    private void initBot(GamePlayer b) {
        int[] dr = DIRS[rnd.nextInt(DIRS.length)];
        b.dirX = dr[0];
        b.dirZ = dr[1];
        b.limit = 6 + rnd.nextInt(14);
        b.returning = false;
        b.phase = 0;
        b.legSteps = 0;
        b.skip = false;
        b.huntSlot = 0;
        b.boxSet = false;
        b.mode = 0;
    }

    private void botRespawn(GamePlayer b) {
        if (state != State.RUNNING || !players.containsKey(b.id)) return;
        int[] cell = chooseStarts(1).get(0);
        claimSquare(b.side, cell[0], cell[1], 1);
        b.alive = true;
        b.lastCell = cell[0] * d + cell[1];
        b.homeX = cell[0];
        b.homeZ = cell[1];
        initBot(b);
        removeBotEntity(b);
        spawnBotEntity(b);
    }

    private void spawnBotEntity(GamePlayer b) {
        if (b.lastCell < 0 || b.side < 0) return;
        b.entity = BotEntities.spawn(cellLocation(b.lastCell, 0f), b.name, botTag(b), sideColor[b.side]);
    }

    /** Etiqueta junto al nombre del bot: estrellas de nivel en partidas solo y en debug; "[Bot]" en equipos. */
    private String botTag(GamePlayer b) {
        return (debug || arena.mode == Arena.PlayMode.SOLO) ? stars(b.level) : "[Bot]";
    }

    /** Estrellas del bot de ese lado para el scoreboard (solo en partidas solo, donde cada lado es un jugador). */
    private String botStars(int side) {
        if (arena.mode != Arena.PlayMode.SOLO) return "";
        for (GamePlayer o : players.values()) {
            if (o.bot && o.side == side) return " §e" + stars(o.level);
        }
        return "";
    }

    private void removeBotEntity(GamePlayer b) {
        if (b.entity != null) {
            b.entity.remove();
            b.entity = null;
        }
    }

    private void removeBotEntities() {
        for (GamePlayer gp : players.values()) {
            if (gp.bot) removeBotEntity(gp);
        }
    }

    private void botTick() {
        if (state != State.RUNNING) return;
        for (GamePlayer b : new ArrayList<>(players.values())) {
            if (b.bot && b.alive && !b.eliminated) botStep(b);
        }
    }

    private boolean botBlocked(GamePlayer b, int x, int z) {
        return x < 0 || z < 0 || x >= w || z >= d || trail[x][z] == b.slot;
    }

    private int[] botRandomDir(GamePlayer b, int cx, int cz) {
        List<int[]> ok = new ArrayList<>(), any = new ArrayList<>();
        for (int[] dr : DIRS) {
            if (botBlocked(b, cx + dr[0], cz + dr[1])) continue;
            any.add(dr);
            if (!(dr[0] == -b.dirX && dr[1] == -b.dirZ)) ok.add(dr);
        }
        List<int[]> pool = ok.isEmpty() ? any : ok;
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    // ---------------------------------------------------------- IA de bots
    // Nivel 1 (fácil):   deambula en línea recta, vuelve a casa al azar y va a mitad de velocidad.
    // Nivel 2 (normal):  hace bucles rectangulares, huye a casa si hay un enemigo cerca y
    //                    ataca estelas enemigas cercanas cuando la suya es corta.
    // Nivel 3 (difícil): bucles más grandes hacia donde hay más terreno (prefiere el enemigo),
    //                    detecta amenazas desde más lejos y persigue estelas a mayor distancia.

    private static final int PH_IDLE = 0, PH_LEG1 = 1, PH_LEG2 = 2, PH_LEG3 = 3, PH_RETURN = 4, PH_CHASE = 5, PH_MERGE = 6;

    private void botStep(GamePlayer b) {
        int cx = b.lastCell / d, cz = b.lastCell % d;
        if (b.level <= 1) {
            b.skip = !b.skip;
            if (b.skip) return;                       // la mitad de velocidad
            if (!b.returning && b.trailCells.size() >= b.limit) b.returning = true;
        }
        boolean wasReturning = b.returning;
        int[] dir = botDecide(b, cx, cz);
        if (dir == null) return;
        int nx = cx + dir[0], nz = cz + dir[1];
        if (botBlocked(b, nx, nz)) return;
        b.dirX = dir[0];
        b.dirZ = dir[1];
        processStep(b, nx, nz, nx * d + nz);
        if (b.alive && b.entity != null) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dir[0], dir[1]));
            b.entity.teleport(cellLocation(nx * d + nz, yaw));
        }
        if (b.level <= 1 && wasReturning && b.alive && b.trailCells.isEmpty() && owner[nx][nz] == b.side + 1) {
            b.returning = false;
            b.limit = 6 + rnd.nextInt(14);
            int[] r = botRandomDir(b, nx, nz);
            if (r != null) {
                b.dirX = r[0];
                b.dirZ = r[1];
            }
        }
    }

    private int[] botDecide(GamePlayer b, int cx, int cz) {
        if (b.level <= 1) return decideWander(b, cx, cz);
        if (b.level >= 3) return decideExpert(b, cx, cz);
        boolean hard = false;
        int tl = b.trailCells.size();
        boolean inHome = owner[cx][cz] == b.side + 1;
        if (inHome && tl == 0 && b.phase >= PH_LEG2) b.phase = PH_IDLE;
        if (tl > 0 && b.phase == PH_IDLE) b.phase = PH_RETURN;

        // Defensa: con estela y un enemigo cerca, volver a casa
        GamePlayer threat = nearestEnemy(b, cx, cz, hard ? 6 : 4);
        if (tl >= 2 && threat != null) b.phase = PH_RETURN;

        // Ataque: perseguir estelas enemigas mientras la propia sea corta
        int atkMax = hard ? 12 : 6;
        if (b.phase == PH_CHASE && tl > atkMax) b.phase = PH_RETURN;
        if (b.phase != PH_RETURN && tl <= atkMax) {
            int[] tgt = nearestEnemyTrail(b, cx, cz, hard ? 14 : 8);
            if (tgt != null) {
                int[] st = stepToward(b, cx, cz, tgt[0], tgt[1]);
                if (st != null) {
                    b.phase = PH_CHASE;
                    return st;
                }
            } else if (b.phase == PH_CHASE) {
                b.phase = tl > 0 ? PH_RETURN : PH_IDLE;
            }
        }

        return runPlan(b, cx, cz, tl, false);
    }

    /** Ejecuta el plan de bucle: salida, lado, vuelta paralela y regreso. */
    private int[] runPlan(GamePlayer b, int cx, int cz, int tl, boolean expert) {
        // Plan: bucle rectangular (salida, lado, vuelta paralela, regreso)
        for (int guard = 0; guard < 6; guard++) {
            switch (b.phase) {
                case PH_IDLE -> {
                    if (expert) planExpert(b, cx, cz);
                    else planLoop(b, cx, cz, false);
                    if (b.phase == PH_IDLE) return botRandomDir(b, cx, cz);
                }
                case PH_LEG1 -> {
                    if (botBlocked(b, cx + b.d1x, cz + b.d1z)) {
                        if (tl == 0) {
                            b.phase = PH_IDLE;
                            return botRandomDir(b, cx, cz);
                        }
                        b.phase = PH_LEG2;
                        b.legSteps = 0;
                    } else if (tl > 0 && b.legSteps >= b.len1) {
                        b.phase = PH_LEG2;
                        b.legSteps = 0;
                    } else {
                        if (tl > 0) b.legSteps++;   // solo cuentan los pasos fuera de casa
                        return new int[]{b.d1x, b.d1z};
                    }
                }
                case PH_LEG2 -> {
                    if (botBlocked(b, cx + b.d2x, cz + b.d2z) || b.legSteps >= b.len2) {
                        b.phase = PH_LEG3;
                        b.legSteps = 0;
                    } else {
                        b.legSteps++;
                        return new int[]{b.d2x, b.d2z};
                    }
                }
                case PH_LEG3 -> {
                    if (botBlocked(b, cx - b.d1x, cz - b.d1z) || b.legSteps >= b.len1) {
                        b.phase = PH_RETURN;
                    } else {
                        b.legSteps++;
                        return new int[]{-b.d1x, -b.d1z};
                    }
                }
                default -> {   // PH_RETURN o PH_CHASE sin objetivo: volver al territorio propio más cercano
                    int[] tgt = nearestOwnCell(b, cx, cz, 30);
                    if (tgt == null) tgt = new int[]{b.homeX, b.homeZ};
                    int[] st = stepToward(b, cx, cz, tgt[0], tgt[1]);
                    return st != null ? st : botRandomDir(b, cx, cz);
                }
            }
        }
        return botRandomDir(b, cx, cz);
    }

    // ============================================================ NIVEL 3: ESTRATEGIA
    // Modos: EXPAND (crecer con bucles eficientes), RAID (robar terreno enemigo y cazar estelas),
    // TURTLE (si va ganando y queda poco tiempo: bucles pequeños y seguros).
    // En equipos: reparto de roles (los bots impares atacan), cada estela enemiga la persigue un solo
    // bot, los bucles no se solapan entre compañeros y la muerte de un aliado pone en alerta al equipo.
    private static final int MODE_EXPAND = 0, MODE_RAID = 1, MODE_TURTLE = 2;

    private static String modeName(int m) {
        return switch (m) {
            case MODE_RAID -> "ataque";
            case MODE_TURTLE -> "defensa";
            default -> "expansión";
        };
    }

    private int[] decideExpert(GamePlayer b, int cx, int cz) {
        if (b.alert > 0) b.alert--;
        int tl = b.trailCells.size();
        boolean inHome = owner[cx][cz] == b.side + 1;
        if (inHome && tl == 0 && b.phase >= PH_LEG2 && b.phase != PH_MERGE) b.phase = PH_IDLE;
        if (tl > 0 && b.phase == PH_IDLE) b.phase = PH_RETURN;
        if (b.phase == PH_IDLE) {
            b.boxSet = false;
            b.huntSlot = 0;
        }

        // 0) Equipos: fusionar estela con un compañero (cierra el bucle al instante y captura las dos)
        if (arena.mode == Arena.PlayMode.TEAM && plugin.getConfig().getBoolean("team-merge-trails", true)) {
            int[] mt = nearestMateTrail(b, cx, cz, 16, tl == 0);
            boolean go = false;
            if (mt != null) {
                int dist = Math.abs(mt[0] - cx) + Math.abs(mt[1] - cz);
                if (tl > 0) {
                    // fuera de casa: fusionar si está cerca, o si hay peligro y el compañero queda más cerca que mi base
                    int[] home = nearestOwnCell(b, cx, cz, 40);
                    int distHome = home == null ? 99 : Math.abs(home[0] - cx) + Math.abs(home[1] - cz);
                    go = exposed(b, cx, cz) ? dist < distHome : (b.phase == PH_MERGE || dist <= 6);
                } else if (b.phase == PH_MERGE) {
                    go = true;
                } else if (b.phase == PH_IDLE && b.mode != MODE_TURTLE
                        && mergeValue(b, cx, cz, mt[0], mt[1]) >= 14 && rnd.nextInt(100) < 70) {
                    go = true;   // salir a encontrarse con él si hay terreno que encerrar entre los dos
                }
                if (go) {
                    int[] st = stepToward(b, cx, cz, mt[0], mt[1]);
                    if (st != null) {
                        b.phase = PH_MERGE;
                        return st;
                    }
                }
            }
            if (!go && b.phase == PH_MERGE) b.phase = tl > 0 ? PH_RETURN : PH_IDLE;
        }

        // 1) Seguridad: si algún tramo de la estela puede ser cortado antes de cerrar el bucle, volver
        if (tl > 0 && b.phase != PH_RETURN && b.phase != PH_MERGE && exposed(b, cx, cz)) b.phase = PH_RETURN;

        // 2) Ataque coordinado sobre estelas enemigas
        int atkMax = b.mode == MODE_TURTLE ? 0 : (b.mode == MODE_RAID ? 16 : 12);
        if (b.phase == PH_CHASE && tl > atkMax) b.phase = PH_RETURN;
        if (b.phase != PH_RETURN && b.phase != PH_MERGE && atkMax > 0 && tl <= atkMax) {
            int[] tgt = pickHuntTarget(b, cx, cz, b.mode == MODE_RAID ? 18 : 14);
            if (tgt != null) {
                int[] st = stepToward(b, cx, cz, tgt[0], tgt[1]);
                if (st != null) {
                    b.phase = PH_CHASE;
                    b.huntSlot = trail[tgt[0]][tgt[1]];
                    return st;
                }
            } else if (b.phase == PH_CHASE) {
                b.phase = tl > 0 ? PH_RETURN : PH_IDLE;
                b.huntSlot = 0;
            }
        }
        return runPlan(b, cx, cz, tl, true);
    }

    /** Celda de estela de un compañero (bot o humano) más cercana; con avoidHome descarta las pegadas a mi territorio. */
    private int[] nearestMateTrail(GamePlayer b, int cx, int cz, int radius, boolean avoidHome) {
        int bestD = Integer.MAX_VALUE;
        int[] best = null;
        for (int x = Math.max(0, cx - radius); x <= Math.min(w - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(d - 1, cz + radius); z++) {
                int t = trail[x][z];
                if (t <= 0 || t == b.slot || t >= bySlot.length) continue;
                GamePlayer o = bySlot[t];
                if (o == null || o.side != b.side || !o.alive || o.trailCells.size() < 2) continue;
                if (avoidHome && touchesOwn(b, x, z)) continue;
                int dist = Math.abs(x - cx) + Math.abs(z - cz);
                if (dist < bestD) {
                    bestD = dist;
                    best = new int[]{x, z};
                }
            }
        }
        return best;
    }

    private boolean touchesOwn(GamePlayer b, int x, int z) {
        for (int k = 0; k < 4; k++) {
            int nx = x + DX[k], nz = z + DZ[k];
            if (nx >= 0 && nz >= 0 && nx < w && nz < d && owner[nx][nz] == b.side + 1) return true;
        }
        return false;
    }

    /** Celdas ajenas dentro del rectángulo entre mi posición y el objetivo (lo que ganarían al fusionar). */
    private int mergeValue(GamePlayer b, int cx, int cz, int tx, int tz) {
        int x0 = Math.min(cx, tx), x1 = Math.max(cx, tx), z0 = Math.min(cz, tz), z1 = Math.max(cz, tz);
        if ((x1 - x0 + 1) * (z1 - z0 + 1) > 400) return 0;
        int v = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (owner[x][z] != b.side + 1) v++;
            }
        }
        return v;
    }

    /** ¿Puede algún enemigo llegar a una celda de mi estela antes de que yo cierre el bucle? */
    private boolean exposed(GamePlayer b, int cx, int cz) {
        int[] home = nearestOwnCell(b, cx, cz, 40);
        int distHome = home == null ? 12 : Math.abs(home[0] - cx) + Math.abs(home[1] - cz);
        int margin = (b.mode == MODE_RAID ? -2 : 0) + (b.mode == MODE_TURTLE ? 1 : 0) + (b.alert > 0 ? 2 : 0);
        for (GamePlayer o : players.values()) {
            if (o == b || o.side == b.side || !o.alive || o.eliminated || o.left || o.lastCell < 0) continue;
            int ox = o.lastCell / d, oz = o.lastCell % d;
            for (int c : b.trailCells) {
                if (Math.abs(ox - c / d) + Math.abs(oz - c % d) <= distHome + margin) return true;
            }
        }
        return false;
    }

    /** Estela enemiga a perseguir: la más cercana, con prioridad a quien me eliminó y sin duplicar a un aliado. */
    private int[] pickHuntTarget(GamePlayer b, int cx, int cz, int radius) {
        double bestScore = Double.MAX_VALUE;
        int[] best = null;
        for (int x = Math.max(0, cx - radius); x <= Math.min(w - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(d - 1, cz + radius); z++) {
                int t = trail[x][z];
                if (t <= 0 || t == b.slot || t >= bySlot.length) continue;
                GamePlayer o = bySlot[t];
                if (o == null || o.side == b.side || !o.alive) continue;
                int dist = Math.abs(x - cx) + Math.abs(z - cz);
                boolean taken = false;
                for (GamePlayer m : players.values()) {
                    if (m == b || !m.bot || m.side != b.side || !m.alive || m.huntSlot != t || m.lastCell < 0) continue;
                    if (Math.abs(m.lastCell / d - x) + Math.abs(m.lastCell % d - z) <= dist) {
                        taken = true;
                        break;
                    }
                }
                if (taken) continue;
                double score = dist - (o == b.revenge ? 8 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{x, z};
                }
            }
        }
        return best;
    }

    private int pickMode(GamePlayer b, int[] cs) {
        int total = w * d, mine = cs[b.side], bestOther = 0;
        for (int s = 0; s < sideCount; s++) {
            if (s != b.side && !sideOut[s]) bestOther = Math.max(bestOther, cs[s]);
        }
        double timeFrac = timeLimited() ? Math.max(0, timeLeft) / (double) arena.duration : 1.0;
        boolean leading = mine > bestOther;
        double lead = (mine - bestOther) * 100.0 / total;
        if (leading && lead >= 3 && timeFrac < 0.35) return MODE_TURTLE;
        if (arena.mode == Arena.PlayMode.TEAM) {
            int idx = 0, my = 0;
            for (GamePlayer o : players.values()) {
                if (o.bot && o.side == b.side) {
                    if (o == b) my = idx;
                    idx++;
                }
            }
            return (my % 2 == 1 || (!leading && timeFrac < 0.5)) ? MODE_RAID : MODE_EXPAND;
        }
        if (!leading && timeFrac < 0.5) return MODE_RAID;
        return rnd.nextInt(100) < 25 ? MODE_RAID : MODE_EXPAND;
    }

    private int[] centroid(int side) {
        long sx = 0, sz = 0, n = 0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (owner[x][z] == side + 1) {
                    sx += x;
                    sz += z;
                    n++;
                }
            }
        }
        return n == 0 ? null : new int[]{(int) (sx / n), (int) (sz / n)};
    }

    /** Territorio enemigo a atacar: en solitario el del líder; en equipos el del rival más cercano. */
    private int[] raidTarget(GamePlayer b, int[] cs) {
        int best = -1;
        double bestV = Double.MAX_VALUE;
        for (int s = 0; s < sideCount; s++) {
            if (s == b.side || sideOut[s] || cs[s] == 0) continue;
            double v;
            if (arena.mode == Arena.PlayMode.TEAM) {
                int[] c = centroid(s);
                v = c == null ? Double.MAX_VALUE : Math.abs(c[0] - b.homeX) + Math.abs(c[1] - b.homeZ);
            } else {
                v = -cs[s];
            }
            if (v < bestV) {
                bestV = v;
                best = s;
            }
        }
        return best < 0 ? null : centroid(best);
    }

    /** Evalúa bucles rectangulares candidatos (valor del terreno / coste, menos riesgo y solapes) y elige el mejor. */
    private void planExpert(GamePlayer b, int cx, int cz) {
        int[] cs = counts();
        b.mode = pickMode(b, cs);
        b.boxSet = false;
        int[] target = b.mode == MODE_RAID ? raidTarget(b, cs) : null;
        int mine = b.side + 1;
        int[] lens1 = b.mode == MODE_TURTLE ? new int[]{3, 4} : new int[]{5, 8, 11, 14};
        int[] lens2 = b.mode == MODE_TURTLE ? new int[]{2, 3} : new int[]{4, 7, 10};
        double bestScore = -1e9;
        int[] bd1 = null, bd2 = null, bbox = null;
        int bl1 = 0, bl2 = 0;
        for (int[] d1 : DIRS) {
            int e = 0, ex = cx, ez = cz;
            while (true) {   // primer paso fuera de mi territorio en esta dirección
                ex += d1[0];
                ez += d1[1];
                e++;
                if (ex < 0 || ez < 0 || ex >= w || ez >= d || e > 45) {
                    e = -1;
                    break;
                }
                if (owner[ex][ez] != mine) break;
            }
            if (e < 0 || trail[ex][ez] == b.slot) continue;
            for (int[] d2 : new int[][]{{-d1[1], d1[0]}, {d1[1], -d1[0]}}) {
                for (int l1 : lens1) {
                    for (int l2 : lens2) {
                        int x1 = ex + d1[0] * (l1 - 1) + d2[0] * (l2 - 1);
                        int z1 = ez + d1[1] * (l1 - 1) + d2[1] * (l2 - 1);
                        int bx0 = Math.min(ex, x1), bx1 = Math.max(ex, x1), bz0 = Math.min(ez, z1), bz1 = Math.max(ez, z1);
                        if (bx0 < 0 || bz0 < 0 || bx1 >= w || bz1 >= d) continue;
                        double value = 0;
                        for (int x = bx0; x <= bx1; x++) {
                            for (int z = bz0; z <= bz1; z++) {
                                int o = owner[x][z];
                                if (o == mine) continue;
                                value += o == 0 ? 2 : (b.mode == MODE_RAID ? 5 : 3);
                            }
                        }
                        double score = value / (2.0 * l1 + l2 + e);
                        int mx = (bx0 + bx1) / 2, mz = (bz0 + bz1) / 2;
                        double risk = 0;
                        for (GamePlayer o : players.values()) {
                            if (o == b || o.side == b.side || !o.alive || o.eliminated || o.left || o.lastCell < 0) continue;
                            int dist = Math.abs(o.lastCell / d - mx) + Math.abs(o.lastCell % d - mz);
                            if (dist < 12) risk += (12 - dist) * (b.mode == MODE_TURTLE ? 0.5 : 0.18);
                        }
                        score -= risk;
                        if (target != null) score += Math.max(0, 40 - (Math.abs(mx - target[0]) + Math.abs(mz - target[1]))) * 0.05;
                        for (GamePlayer o : players.values()) {   // no pisarse con la caja de un compañero
                            if (o == b || !o.bot || o.side != b.side || !o.boxSet) continue;
                            int ox = Math.max(0, Math.min(bx1, o.box[1]) - Math.max(bx0, o.box[0]) + 1);
                            int oz = Math.max(0, Math.min(bz1, o.box[3]) - Math.max(bz0, o.box[2]) + 1);
                            score -= ox * oz * 0.15;
                        }
                        score += rnd.nextDouble() * 0.3;
                        if (score > bestScore) {
                            bestScore = score;
                            bd1 = d1;
                            bd2 = d2;
                            bl1 = l1;
                            bl2 = l2;
                            bbox = new int[]{bx0, bx1, bz0, bz1};
                        }
                    }
                }
            }
        }
        if (bd1 == null) return;   // sin candidatos: se queda en IDLE y dará un paso al azar
        b.d1x = bd1[0];
        b.d1z = bd1[1];
        b.d2x = bd2[0];
        b.d2z = bd2[1];
        b.len1 = bl1;
        b.len2 = bl2;
        b.legSteps = 0;
        System.arraycopy(bbox, 0, b.box, 0, 4);
        b.boxSet = true;
        b.phase = PH_LEG1;
    }

    /** Nivel 1: línea recta con giros al azar; al llegar a su límite de estela vuelve hacia su base. */
    private int[] decideWander(GamePlayer b, int cx, int cz) {
        int dx = b.dirX, dz = b.dirZ;
        if (b.returning) {
            int ex = b.homeX - cx, ez = b.homeZ - cz;
            int sx = Integer.signum(ex), sz = Integer.signum(ez);
            boolean preferX = Math.abs(ex) >= Math.abs(ez);
            int[] first = preferX ? new int[]{sx, 0} : new int[]{0, sz};
            int[] second = preferX ? new int[]{0, sz} : new int[]{sx, 0};
            if ((first[0] != 0 || first[1] != 0) && !botBlocked(b, cx + first[0], cz + first[1])) return first;
            if ((second[0] != 0 || second[1] != 0) && !botBlocked(b, cx + second[0], cz + second[1])) return second;
            return botRandomDir(b, cx, cz);
        }
        if ((dx == 0 && dz == 0) || rnd.nextInt(100) < 18 || botBlocked(b, cx + dx, cz + dz)) {
            return botRandomDir(b, cx, cz);
        }
        return new int[]{dx, dz};
    }

    private void planLoop(GamePlayer b, int cx, int cz, boolean hard) {
        int bestScore = Integer.MIN_VALUE;
        int[] best = null;
        for (int[] dr : DIRS) {
            if (botBlocked(b, cx + dr[0], cz + dr[1])) continue;
            int score = rnd.nextInt(6);
            if (hard) {   // preferir donde haya más terreno ajeno por delante
                for (int k = 1; k <= 14; k++) {
                    int x = cx + dr[0] * k, z = cz + dr[1] * k;
                    if (x < 0 || z < 0 || x >= w || z >= d) break;
                    int o = owner[x][z];
                    if (o == 0) score += 2;
                    else if (o != b.side + 1) score += 3;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = dr;
            }
        }
        if (best == null) return;
        b.d1x = best[0];
        b.d1z = best[1];
        int[] p1 = {-b.d1z, b.d1x}, p2 = {b.d1z, -b.d1x};
        int[] side = room(cx, cz, p1) >= room(cx, cz, p2) ? p1 : p2;
        b.d2x = side[0];
        b.d2z = side[1];
        b.len1 = hard ? 6 + rnd.nextInt(9) : 4 + rnd.nextInt(6);
        b.len2 = hard ? 4 + rnd.nextInt(8) : 3 + rnd.nextInt(5);
        b.legSteps = 0;
        b.phase = PH_LEG1;
    }

    private int room(int cx, int cz, int[] dr) {
        int n = 0;
        while (n < 20) {
            int x = cx + dr[0] * (n + 1), z = cz + dr[1] * (n + 1);
            if (x < 0 || z < 0 || x >= w || z >= d) break;
            n++;
        }
        return n;
    }

    /** Enemigo vivo más cercano (distancia Chebyshev), o null si ninguno está a menos de maxDist. */
    private GamePlayer nearestEnemy(GamePlayer b, int cx, int cz, int maxDist) {
        GamePlayer best = null;
        int bd = Integer.MAX_VALUE;
        for (GamePlayer o : players.values()) {
            if (o == b || o.side == b.side || !o.alive || o.eliminated || o.left || o.lastCell < 0) continue;
            int dist = Math.max(Math.abs(o.lastCell / d - cx), Math.abs(o.lastCell % d - cz));
            if (dist <= maxDist && dist < bd) {
                bd = dist;
                best = o;
            }
        }
        return best;
    }

    /** Celda con estela enemiga más cercana dentro del radio, o null. */
    private int[] nearestEnemyTrail(GamePlayer b, int cx, int cz, int radius) {
        int bestD = Integer.MAX_VALUE;
        int[] best = null;
        for (int x = Math.max(0, cx - radius); x <= Math.min(w - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(d - 1, cz + radius); z++) {
                int t = trail[x][z];
                if (t <= 0 || t == b.slot || t >= bySlot.length) continue;
                GamePlayer o = bySlot[t];
                if (o == null || o.side == b.side || !o.alive) continue;
                int dist = Math.abs(x - cx) + Math.abs(z - cz);
                if (dist < bestD) {
                    bestD = dist;
                    best = new int[]{x, z};
                }
            }
        }
        return best;
    }

    /** Celda de territorio propio más cercana (distancia Manhattan) dentro del radio, o null. */
    private int[] nearestOwnCell(GamePlayer b, int cx, int cz, int radius) {
        int bestD = Integer.MAX_VALUE;
        int[] best = null;
        for (int x = Math.max(0, cx - radius); x <= Math.min(w - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(d - 1, cz + radius); z++) {
                if (owner[x][z] != b.side + 1) continue;
                int dist = Math.abs(x - cx) + Math.abs(z - cz);
                if (dist < bestD) {
                    bestD = dist;
                    best = new int[]{x, z};
                }
            }
        }
        return best;
    }

    /** Paso voraz hacia un objetivo evitando su propia estela; null si no puede avanzar. */
    private int[] stepToward(GamePlayer b, int cx, int cz, int tx, int tz) {
        int ex = tx - cx, ez = tz - cz;
        int sx = Integer.signum(ex), sz = Integer.signum(ez);
        int[][] cands = Math.abs(ex) >= Math.abs(ez) ? new int[][]{{sx, 0}, {0, sz}} : new int[][]{{0, sz}, {sx, 0}};
        for (int[] c : cands) {
            if (c[0] == 0 && c[1] == 0) continue;
            if (!botBlocked(b, cx + c[0], cz + c[1])) return c;
        }
        return null;
    }

    /** Debug: rellena el mapa del jugador hasta el porcentaje indicado (100 = ganar). */
    public boolean debugFill(Player p, double pct) {
        GamePlayer gp = players.get(p.getUniqueId());
        if (state != State.RUNNING || gp == null || gp.side < 0) return false;
        int id = gp.side + 1;
        int target = (int) Math.ceil(w * d * Math.max(0, Math.min(100, pct)) / 100.0);
        int have = 0;
        for (int[] col : owner) for (int v : col) if (v == id) have++;
        outer:
        for (int gx = 0; gx < w; gx++) {
            for (int gz = 0; gz < d; gz++) {
                if (have >= target) break outer;
                if (owner[gx][gz] != id) {
                    owner[gx][gz] = id;
                    dirty.add(gx * d + gz);
                    have++;
                }
            }
        }
        checkFullMap(counts());
        return true;
    }

    public boolean debugKill(Player p) {
        GamePlayer gp = players.get(p.getUniqueId());
        if (state != State.RUNNING || gp == null || gp.side < 0) return false;
        kill(gp, null);
        return true;
    }

    /** @return false si la partida no tiene límite de tiempo (time-limit: false) o no está en curso. */
    public boolean debugSetTime(int seconds) {
        if (state != State.RUNNING || !timeLimited()) return false;
        timeLeft = Math.max(1, seconds);
        return true;
    }

    public List<String> debugInfo(Player p) {
        List<String> out = new ArrayList<>();
        GamePlayer gp = players.get(p.getUniqueId());
        out.add("<yellow>Estado: <white>" + state + " <gray>| " + (timeLimited() ? "tiempo restante" : "transcurrido") + ": <white>" + timeText()
                + " <gray>| modo: <white>" + arena.mode + " <gray>| mapa: <white>" + w + "x" + d);
        if (state != State.RUNNING || gp == null || gp.side < 0) return out;
        int[] counts = counts();
        out.add("<yellow>Tú: <white>" + pct(counts[gp.side], w * d) + " <gray>| estela: <white>" + gp.trailCells.size()
                + " <gray>| kills: <white>" + gp.kills + " <gray>| muertes: <white>" + gp.deaths);
        for (GamePlayer o : players.values()) {
            if (!o.bot) continue;
            out.add("<gray> - " + o.name + " <yellow>" + stars(o.level) + "<gray>: <white>" + pct(counts[o.side], w * d) + " <gray>estela " + o.trailCells.size() + (o.level >= 3 ? " <gray>modo " + modeName(o.mode) : "")
                    + (o.alive ? "" : " <red>(muerto)"));
        }
        return out;
    }

    // ------------------------------------------------------ render y reloj

    private void render(int gx, int gz) {
        Material m;
        int t = trail[gx][gz];
        if (t > 0 && bySlot != null && t < bySlot.length && bySlot[t] != null) {
            m = sideColor[bySlot[t].side].powder();
        } else {
            int o = owner[gx][gz];
            m = (o > 0 && sideColor != null) ? sideColor[o - 1].concrete() : plugin.baseBlock();
        }
        var b = world.getBlockAt(minX + gx, floorY, minZ + gz);
        if (b.getType() != m) b.setType(m, false);
    }

    private void flush() {
        int n = plugin.getConfig().getInt("blocks-per-tick", 1500);
        while (n-- > 0 && !dirty.isEmpty()) {
            int c = dirty.poll();
            render(c / d, c % d);
        }
        if (closing && dirty.isEmpty()) dispose();
    }

    private int[] counts() {
        int[] r = new int[sideCount];
        for (int[] col : owner) {
            for (int v : col) {
                if (v > 0 && v <= sideCount) r[v - 1]++;
            }
        }
        return r;
    }

    private void tickSecond() {
        if (state != State.RUNNING) return;
        elapsed++;
        if (timeLimited()) timeLeft--;
        int total = w * d;
        int[] counts = counts();
        for (GamePlayer gp : players.values()) {
            if (gp.side >= 0) gp.peak = Math.max(gp.peak, counts[gp.side] * 100.0 / total);
        }
        if (checkFullMap(counts)) return;
        Integer[] order = ranking(counts);
        bar.name(Msg.parse("<gold>ColorWar" + (debug ? " <red>[DEBUG]" : "") + " <gray>| <white>" + timeText()));
        if (timeLimited()) {
            bar.progress(Math.max(0f, Math.min(1f, timeLeft / (float) arena.duration)));
        } else {   // sin límite: la barra muestra cuánto del campo domina el líder
            int top = 0;
            for (int v : counts) top = Math.max(top, v);
            bar.progress(Math.max(0f, Math.min(1f, top / (float) total)));
        }
        for (GamePlayer gp : players.values()) updateBoard(gp, counts, order);
        if (timeLimited() && timeLeft <= 0) end();
    }

    private void updateBoard(GamePlayer gp, int[] counts, Integer[] order) {
        if (gp.board == null || gp.side < 0) return;
        int total = w * d;
        for (String l : gp.lines) gp.board.resetScores(l);
        gp.lines.clear();
        List<String> lines = new ArrayList<>();
        if (debug) lines.add("§c§lDEBUG");
        lines.add("§7Tiempo: §f" + timeText());
        lines.add("§7Eliminaciones: §f" + gp.kills);
        lines.add("§7Tu mapa: §f" + pct(counts[gp.side], total));
        lines.add(" ");
        int shown = 0;
        for (Integer s : order) {
            if (sideOut[s] || shown >= 5) continue;
            String nm = sideName[s].length() > 12 ? sideName[s].substring(0, 12) : sideName[s];
            lines.add(sideColor[s].legacy() + "■ §f" + nm + botStars(s) + " §7" + pct(counts[s], total));
            shown++;
        }
        int score = lines.size();
        for (String l : lines) {
            gp.obj.getScore(l).setScore(score--);
            gp.lines.add(l);
        }
    }

    private int sideKills(int side) {
        int k = 0;
        for (GamePlayer gp : players.values()) if (gp.side == side) k += gp.kills;
        return k;
    }

    private Integer[] ranking(int[] counts) {
        Integer[] idx = new Integer[sideCount];
        for (int i = 0; i < sideCount; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> {
            if (sideOut[a] != sideOut[b]) return sideOut[a] ? 1 : -1;
            if (sideOut[a]) return Long.compare(sideOutAt[b], sideOutAt[a]);
            if (counts[a] != counts[b]) return Integer.compare(counts[b], counts[a]);
            return Integer.compare(sideKills(b), sideKills(a));
        });
        return idx;
    }

    // ----------------------------------------------------------------- final

    /**
     * Fin de la partida (último en pie, tiempo o mapa completo). Se congela el juego y el color del ganador
     * avanza solo hasta ocupar todo el campo; solo entonces salen el mensaje de victoria, los premios y los fuegos.
     */
    private void end() {
        if (state != State.RUNNING) return;
        state = State.ENDING;
        if (clockTask != null) clockTask.cancel();
        if (botTask != null) {
            botTask.cancel();
            botTask = null;
        }
        int total = w * d;
        int[] counts = counts();
        for (GamePlayer gp : players.values()) {
            if (gp.side >= 0) gp.peak = Math.max(gp.peak, counts[gp.side] * 100.0 / total);
        }
        Integer[] order = ranking(counts);
        int[] rank = new int[sideCount];
        for (int i = 0; i < sideCount; i++) rank[order[i]] = i + 1;
        String winnerName = sideName[order[0]];
        int winnerSide = order[0];
        for (GamePlayer gp : players.values()) clearTrail(gp);   // las estelas desaparecen
        startSpread(winnerSide, () -> announceResults(counts, order, rank, winnerName, total));
    }

    /** El color del ganador se expande desde su territorio hasta cubrir toda la arena. */
    private void startSpread(int winnerSide, Runnable done) {
        int id = winnerSide + 1;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int remaining = 0;
        for (int gx = 0; gx < w; gx++) {
            for (int gz = 0; gz < d; gz++) {
                if (owner[gx][gz] == id) q.add(new int[]{gx, gz});
                else remaining++;
            }
        }
        if (q.isEmpty()) {   // sin territorio: se siembra desde el centro
            owner[w / 2][d / 2] = id;
            dirty.add((w / 2) * d + d / 2);
            q.add(new int[]{w / 2, d / 2});
            remaining--;
        }
        if (remaining <= 0) {
            done.run();
            return;
        }
        int seconds = Math.max(1, plugin.getConfig().getInt("victory-spread-seconds", 5));
        int perTick = Math.max(8, (int) Math.ceil(remaining / (seconds * 20.0)));
        broadcastBar("<" + sideColor[winnerSide].mm() + "><n> <gray>está conquistando todo el campo...", Msg.p("n", sideName[winnerSide]));
        final int[] ticks = {0};
        spreadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int claimed = 0;
            while (claimed < perTick && !q.isEmpty()) {
                int[] cur = q.poll();
                for (int k = 0; k < 4; k++) {
                    int nx = cur[0] + DX[k], nz = cur[1] + DZ[k];
                    if (nx < 0 || nz < 0 || nx >= w || nz >= d || owner[nx][nz] == id) continue;
                    owner[nx][nz] = id;
                    dirty.add(nx * d + nz);
                    q.add(new int[]{nx, nz});
                    claimed++;
                }
            }
            if (++ticks[0] % 8 == 0) {
                for (GamePlayer gp : players.values()) {
                    Player p = gp.player();
                    if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 0.6f + Math.min(1.4f, ticks[0] / 80f));
                }
            }
            if (q.isEmpty()) {
                spreadTask.cancel();
                spreadTask = null;
                done.run();
            }
        }, 1L, 1L);
    }

    private void broadcastBar(String mm, TagResolver... r) {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) p.sendActionBar(Msg.parse(mm, r));
        }
    }

    /** Con todo el campo del color del ganador: resultados, premios, título de victoria y fuegos artificiales. */
    private void announceResults(int[] counts, Integer[] order, int[] rank, String winnerName, int total) {
        if (state != State.ENDING) return;
        broadcast("<gold><bold>━━ Resultados ━━");
        for (int i = 0; i < Math.min(3, sideCount); i++) {
            int s = order[i];
            broadcast("<yellow>#" + (i + 1) + " <" + sideColor[s].mm() + "><n> <gray>- <white>" + pct(counts[s], total),
                    Msg.p("n", sideName[s]));
        }

        List<GamePlayer> winners = new ArrayList<>();
        for (GamePlayer gp : players.values()) {
            recordResult(gp, rank, counts, total, winnerName, true);
            if (gp.side >= 0 && rank[gp.side] == 1) winners.add(gp);
        }
        for (GamePlayer gp : diedLeft) recordResult(gp, rank, counts, total, winnerName, false);
        diedLeft.clear();
        plugin.stats().save();

        for (GamePlayer gp : winners) {
            Player p = gp.player();
            if (p != null && p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
        }
        startCelebration(winners, sideColor[order[0]]);
        int delay = Math.max(2, plugin.getConfig().getInt("end-delay-seconds", 8));
        finishTask = Bukkit.getScheduler().runTaskLater(plugin, this::finish, delay * 20L);
    }

    private void recordResult(GamePlayer gp, int[] rank, int[] counts, int total, String winnerName, boolean inGame) {
        if (gp.side < 0) return;
        int place = rank[gp.side];
        boolean win = place == 1;
        Player p = gp.player();
        if (!gp.bot && !debug && (!hasBots || plugin.getConfig().getBoolean("bots.stats-with-bots", true))) {
            StatsManager.PlayerStats st = plugin.stats().get(gp.id, gp.name);
            st.games++;
            st.kills += gp.kills;
            st.deaths += gp.deaths;
            if (win) st.wins++;
            st.best = Math.max(st.best, gp.peak);
            if (p != null) plugin.rewards().givePlacement(p, place);
        }
        if (p == null || !inGame) return;
        if (win) {
            p.showTitle(Title.title(Msg.parse("<gold><bold>¡VICTORIA!"),
                    Msg.parse("<gray>¡Conquistaste todo el campo!")));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        } else {
            p.showTitle(Title.title(Msg.parse("<yellow>Puesto #" + place),
                    Msg.parse("<gray>Ganó <white><n>", Msg.p("n", winnerName))));
        }
    }

    /** Partículas y fuegos artificiales alrededor de los ganadores. */
    private void startCelebration(List<GamePlayer> winners, Colors.Entry col) {
        if (winners.isEmpty()) return;
        final int[] ticks = {0};
        celebrationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks[0] += 4;
            for (GamePlayer gp : winners) {
                Player p = gp.player();
                if (p == null || !p.isOnline()) continue;
                Location l = p.getLocation().add(0, 1, 0);
                p.getWorld().spawnParticle(Particle.FIREWORK, l, 40, 1.2, 1.0, 1.2, 0.05);
                if (ticks[0] % 12 == 0) launchFirework(l, col);
            }
        }, 0L, 4L);
    }

    private void launchFirework(Location center, Colors.Entry col) {
        double ang = rnd.nextDouble() * Math.PI * 2, r = 2 + rnd.nextDouble() * 2;
        Location l = center.clone().add(Math.cos(ang) * r, 1 + rnd.nextDouble() * 2, Math.sin(ang) * r);
        Firework fw = center.getWorld().spawn(l, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.fromRGB(col.rgb()))
                .withFade(Color.WHITE)
                .flicker(true)
                .build());
        fw.setFireworkMeta(meta);
        fw.detonate();
    }

    private void finish() {
        closing = true;
        removeBotEntities();
        if (celebrationTask != null) {
            celebrationTask.cancel();
            celebrationTask = null;
        }
        for (GamePlayer gp : new ArrayList<>(players.values())) {
            restorePlayer(gp, gp.player());
            plugin.games().unregister(gp.id);
        }
        players.clear();
        owner = new int[w][d];
        trail = new int[w][d];
        dirty.clear();
        for (int c = 0; c < w * d; c++) dirty.add(c);
    }

    private void cancelTasks() {
        removeBotEntities();
        for (BukkitTask t : new BukkitTask[]{countdownTask, renderTask, clockTask, finishTask, celebrationTask, botTask, spreadTask}) {
            if (t != null) t.cancel();
        }
        countdownTask = renderTask = clockTask = finishTask = celebrationTask = botTask = spreadTask = null;
    }

    private void dispose() {
        cancelTasks();
        plugin.games().removeGame(this);
    }

    /** Detiene la partida de inmediato y deja la arena limpia. */
    public void stopNow() {
        cancelTasks();
        for (GamePlayer gp : new ArrayList<>(players.values())) {
            restorePlayer(gp, gp.player());
            plugin.games().unregister(gp.id);
        }
        players.clear();
        if (floorTouched) {
            Material base = plugin.baseBlock();
            for (int gx = 0; gx < w; gx++) {
                for (int gz = 0; gz < d; gz++) {
                    var b = world.getBlockAt(minX + gx, floorY, minZ + gz);
                    if (b.getType() != base) b.setType(base, false);
                }
            }
        }
        plugin.games().removeGame(this);
    }
}
