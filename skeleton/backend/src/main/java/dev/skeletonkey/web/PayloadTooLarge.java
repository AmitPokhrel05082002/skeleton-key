package dev.skeletonkey.web;

/** 413 PAYLOAD_TOO_LARGE: the request body is larger than the endpoint accepts. */
public class PayloadTooLarge extends RuntimeException {
    public PayloadTooLarge(String message) {
        super(message);
    }
}
