package dev.aibuild.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the messages a player missed while offline, so a build keeps reporting to them after they reconnect.
 *
 * <p>Why this exists: a build takes ten to twenty minutes, and a player who starts one often leaves and comes
 * back. Messages used to be delivered with {@code Bukkit.getPlayer(uuid)} and silently dropped when the player
 * was not online — so the second half of a build looked like silence: no stage names, no scores, no "done". The
 * job itself never stopped; only the reporting vanished.</p>
 *
 * <p>This is a small ring buffer per player. Messages written while they are offline are queued (with their
 * progress state) and flushed on join, in order, with a short header so it is obvious they are late. The buffer
 * is bounded: a player who stays away for a week must not accumulate an unbounded backlog, and the oldest lines
 * are the least interesting ones.</p>
 *
 * <p>Thread-safe: worker threads enqueue, the main thread flushes on join. All state is guarded by the map's
 * monitors through {@code synchronized} blocks on the per-player queue.</p>
 */
public final class OfflineInbox {

    /** Maximum messages held per player; older lines drop off. */
    public static final int MAX_PER_PLAYER = 40;

    /** Maximum distinct players held at once; the least recently touched are evicted. */
    public static final int MAX_PLAYERS = 64;

    /** One queued line: the MiniMessage template, its arguments, and a timestamp for the header. */
    public record Line(String template, Object[] args, long at) {
    }

    private static final Map<UUID, Deque<Line>> INBOX = new HashMap<>();

    private OfflineInbox() {
    }

    /**
     * Queues a message for a player who is currently offline.
     *
     * <p>When the player is online this does nothing: the caller should have delivered it directly.</p>
     *
     * @param uuid     the player
     * @param template the MiniMessage template
     * @param args     positional arguments for {@code <v0>}, {@code <v1>}, ...
     */
    public static void queue(UUID uuid, String template, Object... args) {
        if (uuid == null || template == null || template.isBlank()) return;
        synchronized (INBOX) {
            if (INBOX.size() >= MAX_PLAYERS && !INBOX.containsKey(uuid)) {
                // evict the least recently written player; test servers keep far fewer than this
                UUID oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<UUID, Deque<Line>> e : INBOX.entrySet()) {
                    Deque<Line> q = e.getValue();
                    long last = q.isEmpty() ? 0 : q.peekLast().at();
                    if (last < oldestAt) {
                        oldestAt = last;
                        oldest = e.getKey();
                    }
                }
                if (oldest != null) INBOX.remove(oldest);
            }
            Deque<Line> q = INBOX.computeIfAbsent(uuid, k -> new ArrayDeque<>());
            q.addLast(new Line(template, args == null ? new Object[0] : args.clone(), System.currentTimeMillis()));
            while (q.size() > MAX_PER_PLAYER) q.removeFirst();
        }
    }

    /**
     * Sends everything a player missed and clears the buffer.
     *
     * <p>Called on join. The header names how many lines were missed so a player who walked away for the whole
     * build still gets the summary rather than a wall of text with no context.</p>
     *
     * @param player the player who just joined
     */
    public static void flush(Player player) {
        if (player == null) return;
        Deque<Line> queued;
        synchronized (INBOX) {
            queued = INBOX.remove(player.getUniqueId());
        }
        if (queued == null || queued.isEmpty()) return;
        Msg.send(player, "<gray>Пока вас не было, постройка продолжалась — вот что вы пропустили:</gray>");
        int sent = 0;
        for (Line line : queued) {
            Msg.send(player, line.template(), line.args());
            sent++;
        }
        if (sent > 0) {
            Msg.send(player, "<gray>Конец пропущенного (<white><v0></white> строк).</gray>", sent);
        }
    }

    /**
     * Delivers a message to a player: directly when online, into the buffer when not.
     *
     * <p>This is the method every job should use instead of {@code Msg.to}: it never loses a line.</p>
     *
     * @param uuid     the player, or {@code null} for the console
     * @param template the MiniMessage template
     * @param args     positional arguments
     */
    public static void deliver(UUID uuid, String template, Object... args) {
        if (uuid == null) {
            Msg.send(Bukkit.getConsoleSender(), template, args);
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Msg.send(player, template, args);
            return;
        }
        queue(uuid, template, args);
    }

    /**
     * @param uuid the player
     * @return how many messages are waiting for them
     */
    public static int pending(UUID uuid) {
        if (uuid == null) return 0;
        synchronized (INBOX) {
            Deque<Line> q = INBOX.get(uuid);
            return q == null ? 0 : q.size();
        }
    }

    /**
     * @return how many players currently have queued messages
     */
    public static int playersWithPending() {
        synchronized (INBOX) {
            return INBOX.size();
        }
    }

    /**
     * Drops the buffer for one player (used when a build is cancelled and its backlog is meaningless).
     *
     * @param uuid the player
     */
    public static void clear(UUID uuid) {
        if (uuid == null) return;
        synchronized (INBOX) {
            INBOX.remove(uuid);
        }
    }

    /**
     * Renders a queued line to a component without sending it, for tests and for the live view.
     *
     * @param line the queued line
     * @return the component
     */
    public static Component render(Line line) {
        return Msg.mm(Msg.PREFIX + line.template(), line.args());
    }
}
