package dev.skeletonkey.web;

/** 404 NOT_FOUND. */
public class NotFound extends RuntimeException {
    public NotFound(String message) { super(message); }
}