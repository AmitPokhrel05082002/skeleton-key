package dev.skeletonkey.web;

/** 429 RATE_LIMITED with a Retry-After in whole seconds. */
public class TooManyRequests extends RuntimeException {
    private final long retryAfterSeconds;

    public TooManyRequests(long retryAfterSeconds) {
        super("Too many requests");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
