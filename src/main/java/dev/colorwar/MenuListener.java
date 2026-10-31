package dev.colorwar;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class MenuListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (menu.adminOnly() && e.getWhoClicked() instanceof org.bukkit.entity.Player p && !p.hasPermission("colorwar.admin")) {
            p.closeInventory();
            Msg.send(p, "<red>No tienes permiso.");
            return;
        }
        menu.handleClick(e);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
