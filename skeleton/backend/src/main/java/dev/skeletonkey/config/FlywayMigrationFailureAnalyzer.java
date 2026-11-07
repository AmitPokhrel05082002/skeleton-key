package dev.skeletonkey.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.api.FlywayException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Reports a failed Flyway migration by name instead of a raw stack trace (Req 20.2).
 */
public class FlywayMigrationFailureAnalyzer extends AbstractFailureAnalyzer<FlywayException> {

    private static final Pattern MIGRATION = Pattern.compile(
            "Migration\\s+(\\S+)\\s+failed|Detected failed migration to version\\s+(\\S+)"
                    + "|Script\\s+(\\S+\\.sql)");

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, FlywayException cause) {
        String name = findMigrationName(cause);
        String description = "Database migration "
                + (name != null ? "'" + name + "'" : "(unknown)") + " failed: " + cause.getMessage();
        String action = "Fix the migration (or the database state) and restart. "
                + "Check the flyway_schema_history table for failed entries.";
        return new FailureAnalysis(description, action, cause);
    }

    static String findMigrationName(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            Matcher m = MIGRATION.matcher(message);
            if (m.find()) {
                for (int i = 1; i <= m.groupCount(); i++) {
                    if (m.group(i) != null) {
                        return m.group(i);
                    }
                }
            }
        }
        return null;
    }
}
