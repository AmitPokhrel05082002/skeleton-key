package dev.skeletonkey.web;

import dev.skeletonkey.config.TrustedProxies;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address used for rate limiting. {@code X-Real-IP} is believed only when the direct peer is a trusted
 * proxy and the header holds a single IP literal; otherwise the peer address is used, so a client that reaches the
 * backend directly cannot choose its own bucket.
 */
public final class ClientIp {

    public static final String HEADER = "X-Real-IP";

    private ClientIp() {}

    public static String resolve(HttpServletRequest request, TrustedProxies trusted) {
        return resolve(request.getRemoteAddr(), request.getHeader(HEADER), trusted);
    }

    public static String resolve(String peer, String header, TrustedProxies trusted) {
        if (peer != null && trusted.isTrusted(peer) && header != null && !header.contains(",")
                && TrustedProxies.literal(header) != null) {
            return header.trim();
        }
        return peer == null ? "unknown" : peer;
    }
}
