package dev.skeletonkey.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for stored Definition_Versions, unique per (app code, version). */
public interface DefinitionRepository {
    /** Highest stored version number for the app code, or 0 when none exists. */
    int maxVersion(String appCode);

    /** @throws DuplicateVersionException when (app code, version) already exists */
    void insert(DefinitionVersionRow row);

    Optional<DefinitionVersionRow> findById(UUID id);

    Optional<DefinitionVersionRow> findByAppAndVersion(String appCode, int version);

    /** The highest-numbered PUBLISHED version of the app. */
    Optional<DefinitionVersionRow> findLatestPublished(String appCode);

    /** Distinct app codes, ordered by code point. */
    List<String> findAppCodes();

    /** Every version of the app without its document, highest version first. */
    List<DefinitionVersionInfo> findInfoByApp(String appCode);

    boolean existsByContentHash(String contentHash);

    /** Sets DRAFT to PUBLISHED. Returns false (and changes nothing) when the row is missing or not DRAFT. */
    boolean markPublished(UUID id, Instant publishedAt);
}
