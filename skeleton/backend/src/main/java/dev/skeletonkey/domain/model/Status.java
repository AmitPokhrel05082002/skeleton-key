package dev.skeletonkey.domain.model;

/** A status label declared by a workflow. Labels are plain strings; there is no status enum. */
public record Status(String label, Category category, String color) {
    public enum Category { OPEN, IN_PROGRESS, DONE, REJECTED }
}
