package dev.skeletonkey.domain.load;

import com.fasterxml.jackson.databind.JsonNode;

/** A parsed, schema-valid document with its Content_Hash (64 lowercase hex characters). */
public record LoadedDefinition(JsonNode document, String contentHash) {}
