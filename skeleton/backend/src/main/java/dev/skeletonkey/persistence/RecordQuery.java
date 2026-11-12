package dev.skeletonkey.persistence;

import dev.skeletonkey.domain.access.RowVisibility;
import java.util.Map;

/**
 * A validated list query. All names were checked against the definition by the service; repositories still bind
 * every value as a parameter.
 *
 * @param filters  data field code to expected value (equality on the text form of the stored value)
 * @param sort     what to order by
 * @param visible  row visibility, always applied
 * @param queueOnly true for scope MY_ROLE_QUEUE
 * @param mine     true for scope MINE
 * @param assignedToMe true for scope ASSIGNED_TO_ME
 * @param page     1-based
 */
public record RecordQuery(String appCode, String entityCode, Map<String, String> filters, Sort sort,
                          RowVisibility visibility, boolean mine, boolean assignedToMe, boolean queueOnly,
                          int page, int size) {

    public enum SortKind { FIELD, REFERENCE, CREATED_AT, UPDATED_AT, CREATED_BY, STATUS, STEP, ASSIGNEE, DUE_AT }

    /** @param field data field code when kind is FIELD; @param numeric order numerically */
    public record Sort(SortKind kind, String field, boolean numeric, boolean descending) {}

    public RecordQuery {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
