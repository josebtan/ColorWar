package dev.colorwar;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;

/** Construye el suelo y el borde visible de una arena, repartiendo el trabajo en varios ticks. */
public final class ArenaBuilder {
    private ArenaBuilder() {}

    /** Pone el suelo base y un borde (anillo) justo fuera del área jugable. */
    public static void build(ColorWarPlugin plugin, Arena a, Runnable done) {
        run(plugin, a, true, done);
    }

    /** Quita el borde que puso el plugin (solo bloques del material de borde). */
    public static void clearBorder(ColorWarPlugin plugin, Arena a, Runnable done) {
        run(plugin, a, false, done);
    }

    private static void run(ColorWarPlugin plugin, Arena a, boolean build, Runnable done) {
        World world = a.world == null ? null : Bukkit.getWorld(a.world);
        if (world == null || a.problem() != null) {
            if (done != null) done.run();
            return;
        }
        // Se copian las coordenadas ahora: la arena puede cambiar o borrarse mientras se construye.
        final int minX = a.minX() - 1, minZ = a.minZ() - 1;
        final int cw = a.width() + 2, cd = a.depth() + 2;
        final int floorY = a.floorY;
        final int height = Math.max(1, plugin.borderHeight());
        final Material base = plugin.baseBlock(), border = plugin.borderBlock();
        final int total = cw * cd;
        final int perTick = Math.max(200, plugin.getConfig().getInt("blocks-per-tick", 1500) / 2);
        final int[] idx = {0};

        new BukkitRunnable() {
            @Override
            public void run() {
                int n = perTick;
                while (n-- > 0 && idx[0] < total) {
                    int i = idx[0]++;
                    int gx = i / cd, gz = i % cd;
                    boolean ring = gx == 0 || gz == 0 || gx == cw - 1 || gz == cd - 1;
                    int x = minX + gx, z = minZ + gz;
                    if (ring) {
                        for (int y = floorY; y < floorY + height; y++) {
                            Block b = world.getBlockAt(x, y, z);
                            if (build) {
                                if (b.getType() != border) b.setType(border, false);
                            } else if (b.getType() == border) {
                                b.setType(y == floorY ? base : Material.AIR, false);
                            }
                        }
                    } else if (build) {
                        Block b = world.getBlockAt(x, floorY, z);
                        if (b.getType() != base) b.setType(base, false);
                    }
                }
                if (idx[0] >= total) {
                    cancel();
                    if (done != null) done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
