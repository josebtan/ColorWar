package dev.colorwar;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/**
 * Cuerpo visible de los bots del modo debug.
 * Usa un Mannequin (modelo de jugador, Paper 1.21.9+) y, si el servidor no lo tiene,
 * cae a un ArmorStand. Lleva armadura de cuero del color de su equipo y su nombre encima.
 */
public final class BotEntities {
    public static final String TAG = "colorwar_bot";

    private BotEntities() {}

    public static Entity spawn(Location loc, String name, String stars, Colors.Entry col) {
        Entity ent = null;
        try {
            ent = loc.getWorld().spawnEntity(loc, EntityType.valueOf("MANNEQUIN"));
            MannequinSupport.setup(ent);
        } catch (Throwable t) {   // servidor sin Mannequin
            if (ent != null) ent.remove();
            ent = null;
        }
        if (ent == null) {
            ent = loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
            if (ent instanceof ArmorStand as) {
                as.setArms(true);
                as.setBasePlate(false);
            }
        }
        ent.addScoreboardTag(TAG);
        label(ent, name, stars, col);
        ent.setCustomNameVisible(true);
        ent.setInvulnerable(true);
        ent.setSilent(true);
        ent.setGravity(false);
        ent.setPersistent(false);
        if (ent instanceof LivingEntity le) {
            le.setCollidable(false);
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                eq.setHelmet(dyed(Material.LEATHER_HELMET, col.rgb()));
                eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, col.rgb()));
                eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, col.rgb()));
                eq.setBoots(dyed(Material.LEATHER_BOOTS, col.rgb()));
            }
        }
        return ent;
    }

    /** Nombre sobre el bot: nombre con el color del equipo y estrellas según su nivel. */
    public static void label(Entity ent, String name, String stars, Colors.Entry col) {
        ent.customName(Msg.parse("<" + col.mm() + "><n> <yellow><s>", Msg.p("n", name), Msg.p("s", stars)));
    }

    private static ItemStack dyed(Material m, int rgb) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(rgb));
            it.setItemMeta(lm);
        }
        return it;
    }

    /** Elimina bots que hayan quedado de una sesión anterior (por ejemplo tras un cierre brusco). */
    public static void cleanup() {
        for (World w : org.bukkit.Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(TAG)) e.remove();
            }
        }
    }

    /** Aislado en su propia clase: solo se carga si el servidor tiene Mannequin. */
    private static final class MannequinSupport {
        static void setup(Entity e) {
            if (e instanceof Mannequin m) {
                m.setDescription(null);   // sin texto bajo el nombre
                m.setImmovable(true);
            }
        }
    }
}
