package dev.skeletonkey.persistence;

import java.util.List;

/** Port for the audit log. Entries are only appended and read. */
public interface AuditRepository {
    void append(AuditEntry entry);

    /** Entries for one target, oldest first. */
    List<AuditEntry> findByTarget(String targetType, String targetId);
}
