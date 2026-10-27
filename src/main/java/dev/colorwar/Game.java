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

    private BukkitTask countdownTask, renderTask, clockTask, finishTask, celebrationTask;
    private final List<GamePlayer> diedLeft = new ArrayList<>();   // eliminados que ya salieron (cobran premio al final)
    private int countdown, timeLeft;
    private boolean floorTouched, closing;

    private int sideCount;
    private Colors.Entry[] sideColor;
    private String[] sideName;
    private boolean[] sideOut;
    private long[] sideOutAt;
    private GamePlayer[] bySlot;
    private BossBar bar;
    private final Random rnd = new Random();

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

    private static String fmtTime(int s) {
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------ jugadores

    public void addPlayer(Player p) {
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
        if (state == State.WAITING && players.size() >= arena.minPlayers) {
            startCountdown();
        } else if (state == State.COUNTDOWN && players.size() >= maxPlayers() && countdown > 5) {
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

        if (state == State.COUNTDOWN && players.size() < arena.minPlayers) {
            if (countdownTask != null) {
                countdownTask.cancel();
                countdownTask = null;
            }
            state = State.WAITING;
            broadcast("<red>Cuenta atrás cancelada: faltan jugadores.");
        }
        if (players.isEmpty() && !closing) {
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
        countdown = plugin.getConfig().getInt("countdown-seconds", 15);
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (players.size() < arena.minPlayers) {
                countdownTask.cancel();
                countdownTask = null;
                state = State.WAITING;
                broadcast("<red>Cuenta atrás cancelada: faltan jugadores.");
                return;
            }
            if (countdown <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                start();
                return;
            }
            if (countdown <= 5 || countdown % 5 == 0) {
                for (GamePlayer gp : players.values()) {
                    Player p = gp.player();
                    if (p == null) continue;
                    p.sendActionBar(Msg.parse("<yellow>La partida empieza en <white>" + countdown + "s"));
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
        owner = new int[w][d];
        trail = new int[w][d];
        dirty.clear();

        List<GamePlayer> list = new ArrayList<>(players.values());
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
        int speed = plugin.getConfig().getInt("speed-level", 1);

        for (GamePlayer gp : list) {
            Player p = gp.player();
            if (p == null) continue;
            int[] st = starts.get(gp.side);
            gp.lastCell = st[0] * d + st[1];
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
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        renderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 1L, 1L);
        clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
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
                int idx = gp.trailCells.lastIndexOf(c);
                if (idx >= gp.trailCells.size() - 2) return;   // tolerancia al pisar la celda anterior
                kill(gp, null);
                return;
            }
            GamePlayer victim = bySlot[t];
            boolean safe = arena.mode == Arena.PlayMode.TEAM && victim != null && victim.side == gp.side
                    && plugin.getConfig().getBoolean("friendly-trail-safe", true);
            if (safe) return;
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
        int id = gp.side + 1;
        int gained = gp.trailCells.size();
        for (int c : gp.trailCells) {
            int gx = c / d, gz = c % d;
            trail[gx][gz] = 0;
            owner[gx][gz] = id;
            dirty.add(c);
        }
        gp.trailCells.clear();

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
        Player p = gp.player();
        if (p != null) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            p.sendActionBar(Msg.parse("<green>+" + pct(gained, w * d) + " <gray>de territorio"));
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

        if (killer != null && killer != v) {
            killer.kills++;
            Player kp = killer.player();
            if (kp != null) {
                kp.playSound(kp.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                plugin.rewards().giveKill(kp);
            }
            broadcast("<white><k> <gray>eliminó a <white><v>", Msg.p("k", killer.name), Msg.p("v", v.name));
        } else {
            broadcast("<white><v> <gray>chocó con su propia estela.", Msg.p("v", v.name));
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
        if (state != State.RUNNING || sideCount < 2) return;
        Set<Integer> active = new HashSet<>();
        for (GamePlayer gp : players.values()) {
            if (!gp.left && !gp.eliminated) active.add(gp.side);
        }
        if (active.size() <= 1) end();
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
        timeLeft--;
        int total = w * d;
        int[] counts = counts();
        for (GamePlayer gp : players.values()) {
            if (gp.side >= 0) gp.peak = Math.max(gp.peak, counts[gp.side] * 100.0 / total);
        }
        if (checkFullMap(counts)) return;
        Integer[] order = ranking(counts);
        bar.name(Msg.parse("<gold>ColorWar <gray>| <white>" + fmtTime(Math.max(0, timeLeft))));
        bar.progress(Math.max(0f, Math.min(1f, timeLeft / (float) arena.duration)));
        for (GamePlayer gp : players.values()) updateBoard(gp, counts, order);
        if (timeLeft <= 0) end();
    }

    private void updateBoard(GamePlayer gp, int[] counts, Integer[] order) {
        if (gp.board == null || gp.side < 0) return;
        int total = w * d;
        for (String l : gp.lines) gp.board.resetScores(l);
        gp.lines.clear();
        List<String> lines = new ArrayList<>();
        lines.add("§7Tiempo: §f" + fmtTime(Math.max(0, timeLeft)));
        lines.add("§7Eliminaciones: §f" + gp.kills);
        lines.add("§7Tu mapa: §f" + pct(counts[gp.side], total));
        lines.add(" ");
        int shown = 0;
        for (Integer s : order) {
            if (sideOut[s] || shown >= 5) continue;
            String nm = sideName[s].length() > 12 ? sideName[s].substring(0, 12) : sideName[s];
            lines.add(sideColor[s].legacy() + "■ §f" + nm + " §7" + pct(counts[s], total));
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

    private void end() {
        if (state != State.RUNNING) return;
        state = State.ENDING;
        if (clockTask != null) clockTask.cancel();
        int total = w * d;
        int[] counts = counts();
        for (GamePlayer gp : players.values()) {
            if (gp.side >= 0) gp.peak = Math.max(gp.peak, counts[gp.side] * 100.0 / total);
        }
        Integer[] order = ranking(counts);
        int[] rank = new int[sideCount];
        for (int i = 0; i < sideCount; i++) rank[order[i]] = i + 1;
        String winnerName = sideName[order[0]];

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
        StatsManager.PlayerStats st = plugin.stats().get(gp.id, gp.name);
        st.games++;
        st.kills += gp.kills;
        st.deaths += gp.deaths;
        if (win) st.wins++;
        st.best = Math.max(st.best, gp.peak);
        Player p = gp.player();
        if (p == null) return;
        plugin.rewards().givePlacement(p, place);
        if (!inGame) return;
        if (win) {
            p.showTitle(Title.title(Msg.parse("<gold><bold>¡VICTORIA!"),
                    Msg.parse("<gray>Mapa conquistado: <white>" + pct(counts[gp.side], total))));
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
        for (BukkitTask t : new BukkitTask[]{countdownTask, renderTask, clockTask, finishTask, celebrationTask}) {
            if (t != null) t.cancel();
        }
        countdownTask = renderTask = clockTask = finishTask = celebrationTask = null;
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
