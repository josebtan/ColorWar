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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        arenas = new ArenaManager(this);
        stats = new StatsManager(this);
        rewards = new RewardManager(this);
        games = new GameManager(this);
        arenas.load();
        stats.load();

        PluginCommand cmd = getCommand("colorwar");
        if (cmd != null) {
            ColorWarCommand ex = new ColorWarCommand(this);
            cmd.setExecutor(ex);
            cmd.setTabCompleter(ex);
        }
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
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
    public Location mainLobby() { return arenas.mainLobby(); }

    public Material baseBlock() {
        Material m = Material.matchMaterial(getConfig().getString("base-block", "SMOOTH_STONE"));
        return (m != null && m.isBlock()) ? m : Material.SMOOTH_STONE;
    }
}
