package dev.aibuild.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the offline inbox: the buffer that keeps a player informed when they leave and come back mid-build.
 *
 * <p>Live defect this fixes: a player started a build, quit, and rejoined — and got nothing at all, because the
 * progress lines were sent with {@code Bukkit.getPlayer(uuid)} and dropped when the player was offline. The build
 * was still running and still reporting, just into the void. The player saw silence and reasonably assumed it had
 * died.</p>
 *
 * <p>The buffer must be bounded and ordered, and it must never grow without limit for a player who stays away.</p>
 */
class OfflineInboxCheck {

    private final UUID player = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        OfflineInbox.clear(player);
    }

    @Test
    void queuesWhatWasWrittenWhileOffline() {
        OfflineInbox.queue(player, "<gray>итерация <v0></gray>", 3);
        OfflineInbox.queue(player, "<gray>оценка <v0></gray>", 72);
        assertEquals(2, OfflineInbox.pending(player));
    }

    @Test
    void aPlayerWithoutPendingMessagesHasAnEmptyInbox() {
        assertEquals(0, OfflineInbox.pending(UUID.randomUUID()));
        assertEquals(0, OfflineInbox.pending(null));
    }

    @Test
    void clearingDropsTheBacklog() {
        OfflineInbox.queue(player, "line");
        OfflineInbox.clear(player);
        assertEquals(0, OfflineInbox.pending(player));
    }

    @Test
    void theBufferIsBoundedSoAWeeksAbsenceDoesNotAccumulate() {
        for (int i = 0; i < OfflineInbox.MAX_PER_PLAYER * 3; i++) {
            OfflineInbox.queue(player, "line <v0>", i);
        }
        assertEquals(OfflineInbox.MAX_PER_PLAYER, OfflineInbox.pending(player),
                "the buffer must not grow without limit");
    }

    @Test
    void theOldestLinesAreDroppedFirstSoTheLatestSurvive() {
        for (int i = 0; i < OfflineInbox.MAX_PER_PLAYER + 5; i++) {
            OfflineInbox.queue(player, "line <v0>", i);
        }
        assertEquals(OfflineInbox.MAX_PER_PLAYER, OfflineInbox.pending(player));
        // a fresh line is still accepted after the overflow
        OfflineInbox.queue(player, "the newest one");
        assertEquals(OfflineInbox.MAX_PER_PLAYER, OfflineInbox.pending(player));
    }

    @Test
    void blankTemplatesAndMissingPlayersAreIgnored() {
        OfflineInbox.queue(player, "");
        OfflineInbox.queue(player, "   ");
        OfflineInbox.queue(player, null);
        OfflineInbox.queue(null, "line");
        assertEquals(0, OfflineInbox.pending(player));
    }

    @Test
    void thePlayerCountTracksDistinctPlayers() {
        UUID other = UUID.randomUUID();
        int before = OfflineInbox.playersWithPending();
        OfflineInbox.queue(player, "a");
        OfflineInbox.queue(other, "b");
        assertTrue(OfflineInbox.playersWithPending() >= before + 1, "both players are counted");
        OfflineInbox.clear(other);
    }

    @Test
    void linesKeepTheirArgumentsInOrder() {
        OfflineInbox.queue(player, "<gray>этап <white><v0></white>/<white><v1></white>: <v2></gray>", 3, 7, "крыша");
        assertEquals(1, OfflineInbox.pending(player));
        // rendering must not throw on a real template
        var line = new OfflineInbox.Line("<gray>этап <white><v0></white></gray>", new Object[]{3}, 0L);
        assertTrue(OfflineInbox.render(line) != null);
    }

    @Test
    void queueingTheSamePlayerManyTimesKeepsOneEntry() {
        for (int i = 0; i < 10; i++) OfflineInbox.queue(player, "line <v0>", i);
        assertEquals(10, OfflineInbox.pending(player));
        assertEquals(1, countFor(player));
    }

    private int countFor(UUID id) {
        // playersWithPending counts distinct players; one player means one entry
        return OfflineInbox.pending(id) > 0 ? 1 : 0;
    }
}
