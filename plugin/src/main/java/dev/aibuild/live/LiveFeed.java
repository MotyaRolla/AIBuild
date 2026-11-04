package dev.aibuild.live;

/**
 * What the HTTP layer needs from a live session (implemented by {@link LiveSession}).
 *
 * <p>Separate from {@link LiveSession} so the server can be tested with a fake feed and so the server holds
 * no session internals.</p>
 */
public interface LiveFeed {

    /** The one-time token that identifies this session in the URL. */
    String token();

    /** The build id this session shows. */
    String buildId();

    /** Complete current state as JSON (the page's initial load); never null. */
    String bootstrapJson();

    /** Attaches a subscriber and replays the recent journal into it; close the handle to detach. */
    LiveSubscriber.Handle subscribe(LiveSubscriber subscriber);
}
