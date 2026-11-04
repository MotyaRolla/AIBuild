package dev.aibuild.listen;

import dev.aibuild.AIBuildPlugin;
import dev.aibuild.util.Msg;
import dev.aibuild.util.OfflineInbox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps a player informed across a reconnect.
 *
 * <p>A build runs for ten to twenty minutes, so starting one and leaving the server is normal behaviour. Before
 * this listener the progress lines went to {@code Bukkit.getPlayer(uuid)} and vanished when the player was
 * offline: on return the build looked abandoned even though it was still working. Now the lines are buffered
 * and replayed on join, and the player is told what is still running.</p>
 *
 * <p>The listener is deliberately passive: it never touches a running job, it only reads its state. Cancelling
 * on quit would be the wrong behaviour — the point is that the job survives the player.</p>
 */
public final class PlayerPresenceListener implements Listener {

    private final AIBuildPlugin plugin;

    /**
     * @param plugin the owning plugin, used to look up the live jobs to summarise on join
     */
    public PlayerPresenceListener(AIBuildPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Replays the missed lines and tells the player what is still running.
     *
     * <p>Priority {@code MONITOR} so it runs after other join handlers have settled the player's state.</p>
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        // the inbox is the whole point: everything the job wrote while they were away
        OfflineInbox.flush(player);

        // then a short "still working" line, so they immediately know the build did not die
        var running = plugin.pipeline().jobs();
        for (var job : running) {
            if (job.finished) continue;
            if (!player.getUniqueId().equals(job.owner)) continue;   // console builds have no owner to tell
            Msg.send(player, "<gray>Постройка <white><v0></white> всё ещё идёт — прогресс показывается в боссбаре. "
                    + "Отменить: </gray><white>/aib cancel <v0></white>", job.buildId);
        }
    }

    /**
     * Records that the player left, so later lines can be queued for them.
     *
     * <p>Nothing is cancelled and nothing is dropped: the queue in {@link OfflineInbox} takes over.</p>
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        for (var job : plugin.pipeline().jobs()) {
            if (job.finished) continue;
            if (!player.getUniqueId().equals(job.owner)) continue;   // console builds have no owner to tell
            plugin.getLogger().info("player " + player.getName() + " left with build " + job.buildId
                    + " still running; progress lines will be queued for them");
        }
    }
}
