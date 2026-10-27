package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Selección de dos puntos (como WorldEdit) hecha con el hacha de madera. */
public class Selection {
    public String world;
    public boolean has1, has2;
    public int x1, y1, z1, x2, y2, z2;

    public void reset() {
        world = null;
        has1 = has2 = false;
    }

    public void set(boolean first, String worldName, int x, int y, int z) {
        if (world != null && !world.equals(worldName)) reset();
        world = worldName;
        if (first) {
            x1 = x; y1 = y; z1 = z; has1 = true;
        } else {
            x2 = x; y2 = y; z2 = z; has2 = true;
        }
    }

    public int width() { return Math.abs(x1 - x2) + 1; }
    public int depth() { return Math.abs(z1 - z2) + 1; }

    /** null si el área es válida; si no, el motivo. */
    public String problem() {
        if (!has1 || !has2) return "Faltan puntos: clic izquierdo = pos1, clic derecho = pos2 con el hacha de madera.";
        if (world == null || Bukkit.getWorld(world) == null) return "El mundo no está cargado.";
        if (y1 != y2) return "Los dos puntos deben estar a la misma altura (pos1 Y=" + y1 + ", pos2 Y=" + y2 + ").";
        if (width() < Arena.MIN_SIZE || depth() < Arena.MIN_SIZE)
            return "El área mide " + width() + "x" + depth() + ". Mínimo " + Arena.MIN_SIZE + "x" + Arena.MIN_SIZE + ".";
        if (width() > Arena.MAX_SIZE || depth() > Arena.MAX_SIZE)
            return "El área mide " + width() + "x" + depth() + ". Máximo " + Arena.MAX_SIZE + "x" + Arena.MAX_SIZE + ".";
        return null;
    }

    /** Texto (MiniMessage) con el estado actual de la selección. */
    public String status() {
        String prob = problem();
        if (prob == null) return "<green>Área válida: <white>" + width() + "x" + depth()
                + " <gray>(suelo en Y=" + y1 + "). Usa <white>/cw create [nombre] [solo|team]";
        return "<gray>" + prob;
    }

    public void applyTo(Arena a) {
        a.world = world;
        a.x1 = x1;
        a.z1 = z1;
        a.x2 = x2;
        a.z2 = z2;
        a.floorY = y1;
        a.p1 = true;
        a.p2 = true;
    }

    public static ItemStack wand() {
        ItemStack it = new ItemStack(Material.WOODEN_AXE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Msg.item("<gold>Hacha de selección ColorWar"));
        it.setItemMeta(m);
        return it;
    }
}
