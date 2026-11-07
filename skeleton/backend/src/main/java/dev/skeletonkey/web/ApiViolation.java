package dev.skeletonkey.web;

/** One entry of the error body's {@code violations} array. */
public record ApiViolation(String field, String rule) {}