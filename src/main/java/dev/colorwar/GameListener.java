package dev.colorwar;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

public class GameListener implements Listener {
    private final ColorWarPlugin plugin;

    public GameListener(ColorWarPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean inGame(Player p) {
        return plugin.games().gameOf(p.getUniqueId()) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        Game g = plugin.games().gameOf(e.getPlayer().getUniqueId());
        if (g == null) return;
        if (g.handleMove(e.getPlayer(), to)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.games().leave(e.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inGame(p)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction().isRightClick() && e.getItem() != null && e.getItem().getType() == Material.RED_DYE) {
            plugin.games().leave(p);
            Msg.send(p, "<gray>Has salido de la partida.");
        }
    }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!plugin.getConfig().getBoolean("block-commands", true)) return;
        Player p = e.getPlayer();
        if (!inGame(p) || p.hasPermission("colorwar.admin")) return;
        String first = e.getMessage().toLowerCase(Locale.ROOT).split(" ")[0];
        if (first.equals("/cw") || first.equals("/colorwar") || first.equals("/colorwars")) return;
        e.setCancelled(true);
        Msg.send(p, "<red>No puedes usar comandos en partida. Usa <white>/cw leave</white> para salir.");
    }
}
