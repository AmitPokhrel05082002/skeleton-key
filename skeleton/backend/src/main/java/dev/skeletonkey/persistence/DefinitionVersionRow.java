package dev.skeletonkey.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** One stored Definition_Version. {@code status} is {@code DRAFT} or {@code PUBLISHED}. */
public record DefinitionVersionRow(UUID id, String appCode, int version, String status, String contentHash,
                                   JsonNode document, Instant createdAt, Instant publishedAt) {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    public boolean isDraft() {
        return DRAFT.equals(status);
    }
}
