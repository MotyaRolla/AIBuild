package dev.aibuild.live;

/**
 * How the embedded live-view HTTP server listens and what address it advertises.
 *
 * <p>Separate from {@link LiveViewServer} so a config file (or a test) can describe the server without
 * constructing it. Immutable and normalised on construction: blank hosts become loopback, out-of-range
 * ports are clamped, and the public base URL loses its trailing slash so {@link LiveViewServer#urlFor}
 * can concatenate paths without producing {@code //}.</p>
 *
 * @param bindHost        the interface to listen on; the default is loopback so the plugin never exposes
 *                        the build to the local network (or the internet) unless an operator asks for it
 * @param port            the TCP port, 0 to let the OS pick a free one (the actual port is reported by
 *                        {@link LiveViewServer#port()}); 0 is the safe default for two servers on one host
 * @param publicBaseUrl   the external base URL clients see, e.g. {@code https://builds.example.com};
 *                        empty means "derive it from bind host and port", which is only correct on the
 *                        same machine. A tunnel or reverse proxy is the supported way to expose the view
 * @param maxSubscribers  hard cap on concurrent {@code /events} streams across all tokens; each stream
 *                        occupies one server thread, so this bounds threads as well as memory
 * @param requireSameOrigin when true, a request that carries an {@code Origin} header must match the
 *                        public origin (or the server's own loopback origin); default false so curl and
 *                        privacy-hardened browsers that omit {@code Origin} keep working
 */
public record LiveHttpConfig(String bindHost, int port, String publicBaseUrl, int maxSubscribers,
                             boolean requireSameOrigin) {

    /** Default loopback host. Also the host advertised when {@code publicBaseUrl} is empty. */
    public static final String LOOPBACK = "127.0.0.1";

    public LiveHttpConfig {
        bindHost = bindHost == null || bindHost.isBlank() ? LOOPBACK : bindHost.trim();
        port = Math.max(0, Math.min(65535, port));
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (publicBaseUrl.endsWith("/")) publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
        maxSubscribers = maxSubscribers <= 0 ? 8 : maxSubscribers;
    }

    /**
     * The conservative defaults: loopback, ephemeral port, no advertised URL, 8 concurrent streams,
     * no origin check. Everything an operator would have to opt into stays off.
     */
    public static LiveHttpConfig defaults() {
        return new LiveHttpConfig(LOOPBACK, 0, "", 8, false);
    }

    /** Same settings listening on {@code host:port}, for tests and for a configured operator port. */
    public LiveHttpConfig withAddress(String host, int port) {
        return new LiveHttpConfig(host, port, publicBaseUrl, maxSubscribers, requireSameOrigin);
    }

    /** Same settings advertising {@code url}, which a reverse proxy or tunnel exposes. */
    public LiveHttpConfig withPublicBaseUrl(String url) {
        return new LiveHttpConfig(bindHost, port, url, maxSubscribers, requireSameOrigin);
    }
}
