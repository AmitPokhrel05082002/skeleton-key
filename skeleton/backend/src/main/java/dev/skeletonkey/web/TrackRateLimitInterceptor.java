package dev.skeletonkey.web;

import dev.skeletonkey.config.TrackRateLimiter;
import dev.skeletonkey.config.TrustedProxies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before the track handler (and so before its body is read): every request counts, whatever its outcome.
 * Over the limit it throws {@link TooManyRequests}, which {@link ApiExceptionHandler} turns into 429.
 */
public class TrackRateLimitInterceptor implements HandlerInterceptor {

    private final TrackRateLimiter limiter;
    private final TrustedProxies trusted;

    public TrackRateLimitInterceptor(TrackRateLimiter limiter, TrustedProxies trusted) {
        this.limiter = limiter;
        this.trusted = trusted;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Every answer of this endpoint (200, 404, 422, 429) must not be cached; set it once, before any outcome.
        response.setHeader("Cache-Control", "no-store");
        TrackRateLimiter.Decision d = limiter.tryAcquire(ClientIp.resolve(request, trusted));
        if (!d.allowed()) {
            throw new TooManyRequests(d.retryAfterSeconds());
        }
        return true;
    }
}
