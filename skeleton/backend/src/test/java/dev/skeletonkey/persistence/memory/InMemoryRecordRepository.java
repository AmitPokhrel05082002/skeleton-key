package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.RecordQuery;
import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRecordRepository implements RecordRepository {

    private final Map<UUID, RecordRow> rows = new ConcurrentHashMap<>();
    private final InstanceRepository instances;

    public InMemoryRecordRepository() {
        this(null);
    }

    /** @param instances source of the workflow instance joined to each record, may be null */
    public InMemoryRecordRepository(InstanceRepository instances) {
        this.instances = instances;
    }

    @Override
    public void insert(RecordRow row) {
        if (rows.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("Duplicate record id " + row.id());
        }
    }

    @Override
    public Optional<RecordRow> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean updateData(UUID id, Map<String, Object> data, Instant updatedAt) {
        return rows.computeIfPresent(id, (k, r) -> r.withData(data, updatedAt)) != null;
    }

    private InstanceState instanceOf(RecordRow r) {
        return instances == null ? null : instances.findByRecordId(r.id()).orElse(null);
    }

    @Override
    public Page search(RecordQuery q) {
        UUID user = q.visibility().userId();
        List<RecordRow> matching = rows.values().stream()
                .filter(r -> r.appCode().equals(q.appCode()) && r.entityCode().equals(q.entityCode()))
                .filter(r -> q.filters().entrySet().stream().allMatch(f -> {
                    Object v = r.data().get(f.getKey());
                    return v != null && String.valueOf(v).equals(f.getValue());
                }))
                .filter(r -> q.visibility().isVisible(r.createdBy(), instanceOf(r)))
                .filter(r -> !q.mine() || (user != null && user.equals(r.createdBy())))
                .filter(r -> !q.assignedToMe() || (instanceOf(r) != null && user != null
                        && user.equals(instanceOf(r).assigneeId())))
                .filter(r -> !q.queueOnly() || q.visibility().inRoleQueue(instanceOf(r)))
                .sorted(comparator(q.sort()).thenComparing(RecordRow::id))
                .toList();
        int from = (int) Math.min((long) (q.page() - 1) * q.size(), matching.size());
        int to = Math.min(from + q.size(), matching.size());
        return new Page(matching.subList(from, to), matching.size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<RecordRow> comparator(RecordQuery.Sort s) {
        Comparator<Comparable> cmp = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<RecordRow> base = (a, b) -> {
            Comparable ka = key(a, s);
            Comparable kb = key(b, s);
            if (ka == null || kb == null) {
                return Comparator.<Comparable>nullsLast(Comparator.naturalOrder()).compare(ka, kb);
            }
            return s.descending() ? cmp.compare(kb, ka) : cmp.compare(ka, kb);
        };
        return base;
    }

    @SuppressWarnings("rawtypes")
    private Comparable key(RecordRow r, RecordQuery.Sort s) {
        InstanceState i = s.kind().ordinal() >= RecordQuery.SortKind.STATUS.ordinal() ? instanceOf(r) : null;
        return switch (s.kind()) {
            case FIELD -> {
                Object v = r.data().get(s.field());
                if (v == null) yield null;
                yield s.numeric() ? new BigDecimal(String.valueOf(v)) : String.valueOf(v);
            }
            case REFERENCE -> r.referenceNo();
            case CREATED_AT -> r.createdAt();
            case UPDATED_AT -> r.updatedAt();
            case CREATED_BY -> r.createdBy() == null ? null : r.createdBy().toString();
            case STATUS -> i == null ? null : i.status();
            case STEP -> i == null ? null : i.step();
            case ASSIGNEE -> i == null || i.assigneeId() == null ? null : i.assigneeId().toString();
            case DUE_AT -> i == null ? null : i.dueAt();
        };
    }
}
