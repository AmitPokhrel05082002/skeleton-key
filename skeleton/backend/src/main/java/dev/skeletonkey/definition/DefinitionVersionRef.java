package dev.skeletonkey.definition;

import dev.skeletonkey.persistence.DefinitionVersionInfo;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import java.time.Instant;
import java.util.UUID;

/** Identifies a stored Definition_Version without its document. */
public record DefinitionVersionRef(UUID id, String appCode, int version, String status, String contentHash,
                                   Instant createdAt, Instant publishedAt) {
    static DefinitionVersionRef of(DefinitionVersionRow r) {
        return new DefinitionVersionRef(r.id(), r.appCode(), r.version(), r.status(), r.contentHash(),
                r.createdAt(), r.publishedAt());
    }

    public static DefinitionVersionRef of(DefinitionVersionInfo i) {
        return new DefinitionVersionRef(i.id(), i.appCode(), i.version(), i.status(), i.contentHash(),
                i.createdAt(), i.publishedAt());
    }
}
