package dev.colorwar;

import org.bukkit.Material;

import java.util.List;

public final class Colors {
    private Colors() {}

    /** name = nombre en español, mm = etiqueta MiniMessage, legacy = código § para el scoreboard. */
    public record Entry(String name, Material concrete, Material powder, String mm, String legacy) {}

    public static final List<Entry> PALETTE = List.of(
            new Entry("Rojo", Material.RED_CONCRETE, Material.RED_CONCRETE_POWDER, "red", "§c"),
            new Entry("Azul", Material.BLUE_CONCRETE, Material.BLUE_CONCRETE_POWDER, "blue", "§9"),
            new Entry("Lima", Material.LIME_CONCRETE, Material.LIME_CONCRETE_POWDER, "green", "§a"),
            new Entry("Amarillo", Material.YELLOW_CONCRETE, Material.YELLOW_CONCRETE_POWDER, "yellow", "§e"),
            new Entry("Naranja", Material.ORANGE_CONCRETE, Material.ORANGE_CONCRETE_POWDER, "gold", "§6"),
            new Entry("Morado", Material.PURPLE_CONCRETE, Material.PURPLE_CONCRETE_POWDER, "dark_purple", "§5"),
            new Entry("Cian", Material.CYAN_CONCRETE, Material.CYAN_CONCRETE_POWDER, "dark_aqua", "§3"),
            new Entry("Rosa", Material.PINK_CONCRETE, Material.PINK_CONCRETE_POWDER, "light_purple", "§d"),
            new Entry("Celeste", Material.LIGHT_BLUE_CONCRETE, Material.LIGHT_BLUE_CONCRETE_POWDER, "aqua", "§b"),
            new Entry("Verde", Material.GREEN_CONCRETE, Material.GREEN_CONCRETE_POWDER, "dark_green", "§2"),
            new Entry("Marrón", Material.BROWN_CONCRETE, Material.BROWN_CONCRETE_POWDER, "dark_red", "§4"),
            new Entry("Magenta", Material.MAGENTA_CONCRETE, Material.MAGENTA_CONCRETE_POWDER, "light_purple", "§d")
    );
}
