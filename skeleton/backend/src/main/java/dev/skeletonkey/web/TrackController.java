package dev.skeletonkey.web;

import dev.skeletonkey.app.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public status lookup by reference and contact email. No login; the caller's token, if any, is not used. */
@RestController
public class TrackController {

    public record TrackRequest(String reference, String email) {}

    private final TrackingService service;

    public TrackController(TrackingService service) {
        this.service = service;
    }

    @PostMapping("/api/public/{app}/track")
    @Operation(summary = "Track a request by reference number and contact email")
    @ApiResponse(responseCode = "200", description = "The public status of the request")
    @ApiResponse(responseCode = "404", description = "No matching request (same answer for every mismatch)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "Too many lookups from this address",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TrackingService.TrackResult> track(@PathVariable String app,
                                                             @RequestBody(required = false) TrackRequest body) {
        TrackingService.TrackResult result = service.track(app, body == null ? null : body.reference(),
                body == null ? null : body.email());
        // Cache-Control: no-store is set by TrackRateLimitInterceptor for every outcome of this endpoint.
        return ResponseEntity.ok(result);
    }
}
