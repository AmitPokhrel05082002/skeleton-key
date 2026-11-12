package dev.skeletonkey.persistence.jdbc;

import dev.skeletonkey.persistence.ReferenceCounter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Atomic via a single INSERT ... ON CONFLICT DO UPDATE ... RETURNING statement. */
@Repository
public class JdbcReferenceCounter implements ReferenceCounter {
    private final JdbcClient jdbc;

    public JdbcReferenceCounter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long next(String prefix, int year) {
        return jdbc.sql("""
                INSERT INTO reference_counter (prefix, year, next_value) VALUES (:prefix, :year, 1)
                ON CONFLICT (prefix, year) DO UPDATE SET next_value = reference_counter.next_value + 1
                RETURNING next_value
                """)
                .param("prefix", prefix)
                .param("year", year)
                .query(Long.class)
                .single();
    }
}
