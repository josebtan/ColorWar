package dev.colorwar;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class ColorWarPlugin extends JavaPlugin {
    private ArenaManager arenas;
    private StatsManager stats;
    private RewardManager rewards;
    private GameManager games;
    private Runnable papiUnhook;
    private SelectionManager selections;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
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
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papiUnhook = PapiHook.register(this);
                getLogger().info("Placeholders registrados en PlaceholderAPI: %colorwar_...% (usa /cw placeholders).");
            } catch (Throwable t) {
                getLogger().warning("No se pudieron registrar los placeholders: " + t.getMessage());
            }
        }
        getLogger().info("ColorWar activado con " + arenas.all().size() + " arena(s).");
    }

    @Override
    public void onDisable() {
        if (papiUnhook != null) papiUnhook.run();
        if (games != null) games.shutdown();
        if (stats != null) stats.save();
        if (arenas != null) arenas.save();
    }

    /** Actualiza un config.yml antiguo conservando sus comentarios: suelo blanco y consejo de F5. */
    private void migrateConfig() {
        File f = new File(getDataFolder(), "config.yml");
        if (!f.exists()) return;
        try {
            String txt = Files.readString(f.toPath());
            if (txt.contains("config-version")) return;
            txt = txt.replaceFirst("(?m)^base-block:\\s*SMOOTH_STONE", "base-block: WHITE_CONCRETE");
            if (!txt.contains("tips:")) {
                txt += "\n# Consejos\ntips:\n  third-person: true   # al empezar, sugiere pulsar F5 para la tercera persona\n";
            }
            txt += "\nconfig-version: 2\n";
            Files.writeString(f.toPath(), txt);
            reloadConfig();
            getLogger().info("config.yml actualizado a la versión 2 (suelo blanco y consejos).");
        } catch (IOException e) {
            getLogger().warning("No se pudo actualizar config.yml: " + e.getMessage());
        }
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
        Material m = Material.matchMaterial(getConfig().getString("base-block", "WHITE_CONCRETE"));
        return (m != null && m.isBlock()) ? m : Material.WHITE_CONCRETE;
    }
}
