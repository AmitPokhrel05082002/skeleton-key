package dev.skeletonkey.domain.model;

import java.util.UUID;

public record Assignee(Strategy strategy, String role, String field) {
    public enum Strategy { ROLE_QUEUE, LEAST_LOADED, ROUND_ROBIN, INITIATOR, FROM_FIELD }
}
