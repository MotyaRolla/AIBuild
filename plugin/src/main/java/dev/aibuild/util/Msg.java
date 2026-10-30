package dev.aibuild.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/** MiniMessage based chat helper. Thread-safe (Adventure audiences are). */
public final class Msg {
    public static final String PREFIX = "<gradient:#7ee8fa:#80ff72><bold>AIBuild</bold></gradient> <dark_gray>»</dark_gray> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    /** Parses MiniMessage; {@code <v0>..<v9>} placeholders are filled with unparsed (escaped) args. */
    public static Component mm(String template, Object... args) {
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i < args.length; i++) b.resolver(Placeholder.unparsed("v" + i, String.valueOf(args[i])));
        return MM.deserialize(template, b.build());
    }

    public static void send(CommandSender to, String template, Object... args) {
        if (to != null) to.sendMessage(mm(PREFIX + template, args));
    }

    public static void raw(CommandSender to, String template, Object... args) {
        if (to != null) to.sendMessage(mm(template, args));
    }

    /** Sends to an online player by uuid, or to the console when uuid == null. */
    public static void to(UUID uuid, String template, Object... args) {
        if (uuid == null) {
            send(Bukkit.getConsoleSender(), template, args);
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) send(p, template, args);
    }

    public static String escape(String s) {
        return s == null ? "" : MM.escapeTags(s);
    }
}
