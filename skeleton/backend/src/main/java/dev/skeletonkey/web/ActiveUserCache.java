package dev.skeletonkey.web;

import dev.skeletonkey.persistence.UserRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-instance cache of the {@code active} flag, so a deactivated user's token stops working on the next request
 * without a database read per request. Entries live at most {@code ttlSeconds}; {@link #evict(UUID)} drops one at
 * once (the admin service calls it when it changes {@code active}). Bounded: when it grows past {@code maxEntries}
 * it is cleared. Fails closed: a repository error counts as inactive.
 */
@Component
public class ActiveUserCache implements ActiveUserCheck {
    private static final Logger log = LoggerFactory.getLogger(ActiveUserCache.class);
    static final int MAX_TTL_SECONDS = 30;
    static final int MAX_ENTRIES = 10_000;

    private record Entry(boolean active, long expiresAtMillis) {}

    private final UserRepository users;
    private final long ttlMillis;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public ActiveUserCache(UserRepository users,
                           @Value("${skeletonkey.auth.active-cache-seconds:30}") int ttlSeconds) {
        this(users, ttlSeconds, Clock.systemUTC());
    }

    public ActiveUserCache(UserRepository users, int ttlSeconds, Clock clock) {
        if (ttlSeconds < 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "skeletonkey.auth.active-cache-seconds must be between 0 and " + MAX_TTL_SECONDS);
        }
        this.users = users;
        this.ttlMillis = ttlSeconds * 1000L;
        this.clock = clock;
    }

    @Override
    public boolean isActive(UUID userId) {
        if (userId == null) {
            return false;
        }
        long now = clock.millis();
        if (ttlMillis > 0) {
            Entry e = cache.get(userId);
            if (e != null && e.expiresAtMillis() > now) {
                return e.active();
            }
        }
        boolean active;
        try {
            active = users.isActive(userId);
        } catch (RuntimeException ex) {
            log.warn("Active check failed; treating the user as inactive", ex);
            return false;
        }
        if (ttlMillis > 0) {
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(userId, new Entry(active, now + ttlMillis));
        }
        return active;
    }

    /** Forgets one user, so the next request reads the current value. */
    public void evict(UUID userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }
}
