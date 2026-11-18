package dev.skeletonkey.persistence;

/**
 * Admin user listing request. {@code q}, {@code role} and {@code active} are optional filters (null = no filter).
 * The sort column comes from a closed enum, so no request text ever reaches the ORDER BY clause.
 */
public record UserSearch(String q, String role, Boolean active, int page, int size, Field sortField, boolean asc) {

    /** Sortable columns and the API names that select them. */
    public enum Field {
        USERNAME("username", "lower(u.username)"),
        DISPLAY_NAME("displayName", "lower(u.display_name)"),
        ACTIVE("active", "u.active"),
        CREATED_AT("createdAt", "u.created_at");

        private final String apiName;
        private final String sql;

        Field(String apiName, String sql) {
            this.apiName = apiName;
            this.sql = sql;
        }

        public String apiName() {
            return apiName;
        }

        public String sql() {
            return sql;
        }

        public static java.util.Optional<Field> fromApi(String name) {
            for (Field f : values()) {
                if (f.apiName.equals(name)) {
                    return java.util.Optional.of(f);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** Escapes {@code \}, {@code %} and {@code _} so {@code q} is matched literally by LIKE with ESCAPE '\'. */
    public static String likePattern(String q) {
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
