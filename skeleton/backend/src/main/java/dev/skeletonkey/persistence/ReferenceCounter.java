package dev.skeletonkey.persistence;

/** Port for atomic per-prefix, per-year reference number sequences (Req 7.7). */
public interface ReferenceCounter {
    /** Returns the next value of the sequence, starting at 1. Atomic under concurrency. */
    long next(String prefix, int year);

    /** Formats a reference number as {@code PREFIX-YYYY-000123}. */
    static String format(String prefix, int year, long value) {
        return String.format("%s-%d-%06d", prefix, year, value);
    }
}
