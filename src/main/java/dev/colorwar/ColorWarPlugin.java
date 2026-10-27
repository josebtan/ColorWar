package dev.colorwar;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ColorWarPlugin extends JavaPlugin {
    private ArenaManager arenas;
    private StatsManager stats;
    private RewardManager rewards;
    private GameManager games;
    private SelectionManager selections;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        arenas = new ArenaManager(this);
        stats = new StatsManager(this);
        rewards = new RewardManager(this);
        games = new GameManager(this);
        selections = new SelectionManager();
        arenas.load();
        stats.load();
        BotEntities.cleanup();

        PluginCommand cmd = getCommand("colorwar");
        if (cmd != null) {
            ColorWarCommand ex = new ColorWarCommand(this);
            cmd.setExecutor(ex);
            cmd.setTabCompleter(ex);
        }
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new SelectionListener(this), this);
        getLogger().info("ColorWar activado con " + arenas.all().size() + " arena(s).");
    }

    @Override
    public void onDisable() {
        if (games != null) games.shutdown();
        if (stats != null) stats.save();
        if (arenas != null) arenas.save();
    }

    public ArenaManager arenas() { return arenas; }
    public StatsManager stats() { return stats; }
    public RewardManager rewards() { return rewards; }
    public GameManager games() { return games; }
    public SelectionManager selections() { return selections; }
    public Location mainLobby() { return arenas.mainLobby(); }

    public Material borderBlock() {
        Material m = Material.matchMaterial(getConfig().getString("border-block", "BLACK_CONCRETE"));
        return (m != null && m.isBlock()) ? m : Material.BLACK_CONCRETE;
    }

    public int borderHeight() {
        return Math.max(1, Math.min(10, getConfig().getInt("border-height", 3)));
    }

    /** Detiene la partida, quita el borde y borra la arena. */
    public void deleteArena(Arena a) {
        Game g = games.gameOf(a);
        if (g != null) g.stopNow();
        if (a.problem() == null) ArenaBuilder.clearBorder(this, a, null);
        arenas.remove(a);
    }

    public Material baseBlock() {
        Material m = Material.matchMaterial(getConfig().getString("base-block", "SMOOTH_STONE"));
        return (m != null && m.isBlock()) ? m : Material.SMOOTH_STONE;
    }
}
