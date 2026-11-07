package dev.skeletonkey.web;

/** 409 conflict with a specific code (default CONFLICT). */
public class Conflict extends RuntimeException {
    private final String code;

    public Conflict(String message) { this("CONFLICT", message); }

    public Conflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}