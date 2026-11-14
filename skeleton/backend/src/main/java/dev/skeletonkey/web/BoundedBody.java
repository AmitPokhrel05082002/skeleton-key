package dev.skeletonkey.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads a request body as UTF-8 text without ever reading more than {@link #MAX_BYTES} + 1 bytes. */
public final class BoundedBody {
    /** 1 MiB. A constant on purpose: it is not configuration. */
    public static final int MAX_BYTES = 1_048_576;

    private BoundedBody() {}

    /** @throws PayloadTooLarge when the body is longer than {@link #MAX_BYTES} bytes */
    public static String read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            throw tooLarge();
        }
        return read(request.getInputStream());
    }

    /** Reads at most {@code MAX_BYTES + 1} bytes; the extra byte proves the body is too large. */
    public static String read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static PayloadTooLarge tooLarge() {
        return new PayloadTooLarge("Request body is larger than " + MAX_BYTES + " bytes");
    }
}
