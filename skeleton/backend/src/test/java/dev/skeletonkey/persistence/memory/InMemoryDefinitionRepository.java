package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.DefinitionVersionInfo;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.DuplicateVersionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class InMemoryDefinitionRepository implements DefinitionRepository {
    private final List<DefinitionVersionRow> rows = new ArrayList<>();
    /** Test hook: number of upcoming inserts that fail as if another writer took the version number. */
    public int failNextInsertsWithDuplicate;

    @Override
    public synchronized int maxVersion(String appCode) {
        return rows.stream().filter(r -> r.appCode().equals(appCode)).mapToInt(DefinitionVersionRow::version).max().orElse(0);
    }

    @Override
    public synchronized void insert(DefinitionVersionRow row) {
        if (failNextInsertsWithDuplicate > 0) {
            failNextInsertsWithDuplicate--;
            throw new DuplicateVersionException(row.appCode(), row.version());
        }
        if (rows.stream().anyMatch(r -> r.appCode().equals(row.appCode()) && r.version() == row.version())) {
            throw new DuplicateVersionException(row.appCode(), row.version());
        }
        rows.add(row);
    }

    @Override
    public synchronized Optional<DefinitionVersionRow> findById(UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public synchronized Optional<DefinitionVersionRow> findByAppAndVersion(String appCode, int version) {
        return rows.stream().filter(r -> r.appCode().equals(appCode) && r.version() == version).findFirst();
    }

    @Override
    public synchronized Optional<DefinitionVersionRow> findLatestPublished(String appCode) {
        return rows.stream().filter(r -> r.appCode().equals(appCode) && DefinitionVersionRow.PUBLISHED.equals(r.status()))
                .max(Comparator.comparingInt(DefinitionVersionRow::version));
    }

    @Override
    public synchronized List<String> findAppCodes() {
        return rows.stream().map(DefinitionVersionRow::appCode).distinct().sorted().toList();
    }

    @Override
    public synchronized List<DefinitionVersionInfo> findInfoByApp(String appCode) {
        return rows.stream().filter(r -> r.appCode().equals(appCode))
                .sorted(Comparator.comparingInt(DefinitionVersionRow::version).reversed())
                .map(r -> new DefinitionVersionInfo(r.id(), r.appCode(), r.version(), r.status(), r.contentHash(),
                        r.createdAt(), r.publishedAt()))
                .toList();
    }

    @Override
    public synchronized boolean existsByContentHash(String contentHash) {
        return rows.stream().anyMatch(r -> r.contentHash().equals(contentHash));
    }

    @Override
    public synchronized boolean markPublished(UUID id, Instant publishedAt) {
        for (int i = 0; i < rows.size(); i++) {
            DefinitionVersionRow r = rows.get(i);
            if (r.id().equals(id) && r.isDraft()) {
                rows.set(i, new DefinitionVersionRow(r.id(), r.appCode(), r.version(), DefinitionVersionRow.PUBLISHED,
                        r.contentHash(), r.document(), r.createdAt(), publishedAt));
                return true;
            }
        }
        return false;
    }
}
