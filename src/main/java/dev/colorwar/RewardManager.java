package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class RewardManager {
    private final ColorWarPlugin plugin;

    public RewardManager(ColorWarPlugin plugin) {
        this.plugin = plugin;
    }

    /** Participación + premio por puesto (1, 2, 3...). */
    public void givePlacement(Player p, int place) {
        FileConfiguration c = plugin.getConfig();
        int coins = c.getInt("rewards.participation.coins", 0);
        List<String> cmds = new ArrayList<>(c.getStringList("rewards.participation.commands"));
        String base = "rewards.placements." + place;
        if (c.isConfigurationSection(base)) {
            coins += c.getInt(base + ".coins", 0);
            cmds.addAll(c.getStringList(base + ".commands"));
        }
        grant(p, coins, cmds);
    }

    public void giveKill(Player p) {
        FileConfiguration c = plugin.getConfig();
        grant(p, c.getInt("rewards.per-kill.coins", 0), c.getStringList("rewards.per-kill.commands"));
    }

    private void grant(Player p, int coins, List<String> cmds) {
        if (coins > 0) {
            plugin.stats().get(p.getUniqueId(), p.getName()).coins += coins;
            Msg.send(p, "<green>Recompensa: <gold>+" + coins + " monedas");
        }
        for (String cmd : cmds) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
    }
}
