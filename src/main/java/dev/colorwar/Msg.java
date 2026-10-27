package dev.colorwar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gradient:#ff5555:#5555ff><bold>ColorWar</bold></gradient> <dark_gray>» <gray>";

    private Msg() {}

    public static Component parse(String s, TagResolver... r) {
        return MM.deserialize(s, r);
    }

    /** Para nombres/lore de items: sin cursiva por defecto. */
    public static Component item(String s, TagResolver... r) {
        return parse(s, r).decoration(TextDecoration.ITALIC, false);
    }

    public static void send(CommandSender to, String s, TagResolver... r) {
        to.sendMessage(parse(PREFIX + s, r));
    }

    public static TagResolver p(String key, String value) {
        return Placeholder.unparsed(key, value);
    }
}
