package dev.colorwar;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public abstract class Menu implements InventoryHolder {
    protected final ColorWarPlugin plugin;
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(ColorWarPlugin plugin, int rows, String title) {
        this.plugin = plugin;
        this.inv = Bukkit.createInventory(this, rows * 9, Msg.parse(title));
    }

    protected abstract void build(Player viewer);

    /** Los menús de administración se cierran en cuanto el jugador pierde el permiso. */
    public boolean adminOnly() {
        return false;
    }

    public void open(Player p) {
        refresh(p);
        p.openInventory(inv);
    }

    public void refresh(Player p) {
        inv.clear();
        actions.clear();
        build(p);
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    public void handleClick(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> a = actions.get(e.getRawSlot());
        if (a != null) a.accept(e);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    protected static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.item(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Msg.item(s));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }
}
