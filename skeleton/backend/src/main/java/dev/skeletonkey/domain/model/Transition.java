package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

public record Transition(String from, String to, String on, JsonNode when, int priority, String status) {
    public static final int DEFAULT_PRIORITY = 100;
}
