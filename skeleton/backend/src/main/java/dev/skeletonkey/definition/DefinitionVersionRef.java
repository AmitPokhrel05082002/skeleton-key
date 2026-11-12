package dev.skeletonkey.definition;

import dev.skeletonkey.persistence.DefinitionVersionRow;
import java.util.UUID;

/** Identifies a stored Definition_Version without its document. */
public record DefinitionVersionRef(UUID id, String appCode, int version, String status, String contentHash) {
    static DefinitionVersionRef of(DefinitionVersionRow r) {
        return new DefinitionVersionRef(r.id(), r.appCode(), r.version(), r.status(), r.contentHash());
    }
}
