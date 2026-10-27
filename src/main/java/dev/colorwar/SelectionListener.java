package dev.colorwar;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Hacha de madera: clic izquierdo = pos1, clic derecho = pos2 (igual que WorldEdit). */
public class SelectionListener implements Listener {
    private final ColorWarPlugin plugin;

    public SelectionListener(ColorWarPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act != Action.LEFT_CLICK_BLOCK && act != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.WOODEN_AXE) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("colorwar.admin")) return;
        if (plugin.games().gameOf(p.getUniqueId()) != null) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        e.setCancelled(true);
        boolean first = act == Action.LEFT_CLICK_BLOCK;
        Selection sel = plugin.selections().getOrCreate(p.getUniqueId());
        sel.set(first, b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        Msg.send(p, "<yellow>" + (first ? "Pos1" : "Pos2") + " marcada <gray>(" + b.getX() + ", " + b.getY()
                + ", " + b.getZ() + "). " + sel.status());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.selections().remove(e.getPlayer().getUniqueId());
    }
}
