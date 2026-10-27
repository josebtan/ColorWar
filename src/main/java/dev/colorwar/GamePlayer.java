package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GamePlayer {
    public final UUID id;
    public final String name;

    // Solo para bots del modo debug
    public boolean bot;
    public boolean returning;
    public int dirX, dirZ, limit, homeX, homeZ;
    public int side = -1;
    public int slot = 0;
    public boolean alive = false, eliminated = false, left = false;
    public int kills, deaths;
    public double peak;
    public int lastCell = -1;
    public final List<Integer> trailCells = new ArrayList<>();

    // Estado guardado del jugador para restaurarlo al salir
    public ItemStack[] savedContents, savedArmor;
    public GameMode savedMode;
    public Location savedLoc;
    public int savedFood = 20;

    public Scoreboard board;
    public Objective obj;
    public final List<String> lines = new ArrayList<>();

    public GamePlayer(Player p) {
        this.id = p.getUniqueId();
        this.name = p.getName();
    }

    /** Constructor de bot (jugador falso para probar en solitario). */
    public GamePlayer(String botName) {
        this.id = UUID.randomUUID();
        this.name = botName;
        this.bot = true;
    }

    public Player player() {
        if (bot) return null;
        return Bukkit.getPlayer(id);
    }
}
