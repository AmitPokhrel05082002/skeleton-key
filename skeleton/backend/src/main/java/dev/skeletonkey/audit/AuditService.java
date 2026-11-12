package dev.skeletonkey.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.AuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit log entries (Req 12.10). Joins the caller's transaction (MANDATORY), so a failed audit write
 * propagates and rolls the operation back (Req 12.11).
 */
@Service
public class AuditService {

    public static final String CREATE = "create";
    public static final String UPDATE = "update";
    public static final String PUBLISH = "publish";

    private final AuditRepository repository;
    private final Clock clock;

    @Autowired
    public AuditService(AuditRepository repository, ObjectProvider<Clock> clock) {
        this(repository, clock.getIfAvailable(Clock::systemUTC));
    }

    public AuditService(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Appends one entry. {@code actorId} may be null for anonymous callers. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void record(UUID actorId, String action, String targetType, String targetId, JsonNode details) {
        repository.append(new AuditEntry(Instant.now(clock), actorId, action, targetType, targetId, details));
    }
}
