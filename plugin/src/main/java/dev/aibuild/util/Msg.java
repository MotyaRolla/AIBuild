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

    /**
     * A message with one clickable, underlined link appended in place of {@code {link}}.
     *
     * <p>Built by attaching a real {@link net.kyori.adventure.text.event.ClickEvent} to the component instead of
     * writing {@code <click:open_url:...>} into the template with a placeholder inside the quotes. MiniMessage
     * resolves placeholders in the message body but not inside a tag argument, so the quoted placeholder is
     * never substituted and the player sees the raw {@code <click:...>} markup instead of a link. Doing it in
     * code also means the URL is never parsed as markup, so it cannot inject tags.</p>
     */
    public static Component link(String template, String url, Object... args) {
        Component base = mm(template.replace("{link}", ""), args);
        if (url == null || url.isBlank()) return base;
        Component shown = Component.text(url)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
        try {
            shown = shown.clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url));
        } catch (IllegalArgumentException e) {
            // openUrl rejects anything it cannot open (a space, a stray '<'). The address is operator-set
            // config, so a typo must degrade to plain text rather than break the build that is announcing it.
            return base.append(shown);
        }
        return base.append(shown);
    }

    /** Sends a {@link #link} message. */
    public static void sendLink(CommandSender to, String template, String url, Object... args) {
        if (to != null) to.sendMessage(link(PREFIX + template, url, args));
    }

    /** Sends a {@link #link} message to an online player by uuid, or to the console when uuid == null. */
    public static void toLink(UUID uuid, String template, String url, Object... args) {
        if (uuid == null) {
            sendLink(Bukkit.getConsoleSender(), template, url, args);
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) sendLink(p, template, url, args);
    }
}
