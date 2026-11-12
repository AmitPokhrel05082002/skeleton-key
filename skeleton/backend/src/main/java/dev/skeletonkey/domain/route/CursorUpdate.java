package dev.skeletonkey.domain.route;

import java.util.UUID;

/** Plain-data request to move an assignment cursor; persisted only with the rest of an action outcome. */
public record CursorUpdate(String appCode, String workflowCode, String stepCode, UUID lastUserId) {}
