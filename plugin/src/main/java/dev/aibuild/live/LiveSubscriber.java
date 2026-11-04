package dev.aibuild.live;

/**
 * One open live-view connection, as seen by {@link LiveSession}.
 *
 * <p>{@link #send} is called from the session's fan-out thread and must not block: the HTTP layer that
 * implements it writes to a bounded per-connection queue and closes the connection when the client falls
 * behind. Implementations are responsible for their own thread safety.</p>
 */
public interface LiveSubscriber {

    /** Delivers one complete Server-Sent-Events frame (already formatted, ending in a blank line). */
    void send(String frame);

    /** Handle returned by {@link LiveFeed#subscribe}; closing it detaches the subscriber. */
    interface Handle {
        void close();
    }
}
