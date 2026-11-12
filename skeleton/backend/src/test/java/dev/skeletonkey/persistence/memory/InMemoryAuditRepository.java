package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.AuditRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class InMemoryAuditRepository implements AuditRepository {
    private final List<AuditEntry> entries = new ArrayList<>();

    @Override
    public synchronized void append(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public synchronized List<AuditEntry> findByTarget(String targetType, String targetId) {
        return entries.stream()
                .filter(e -> e.targetType().equals(targetType) && Objects.equals(e.targetId(), targetId))
                .sorted(Comparator.comparing(AuditEntry::eventTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }
}
