package dev.skeletonkey.app;

import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.persistence.TrackingRepository;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public status tracking. A record is shown only when the reference and the contact email both match; every
 * failure is the same 404, so the endpoint cannot be used to find out which references exist. The answer holds only
 * public-safe facts. Nothing here logs the reference or the email.
 */
@Service
public class TrackingService {

    public static final int REFERENCE_MAX = 128;
    public static final int EMAIL_MAX = 254;
    static final int TITLE_MAX = 200;
    public static final String NO_MATCH_MESSAGE = "No matching request found";

    public record StatusView(String label, String color, String category) {}

    public record TimelineEntry(Instant at, String status) {}

    /** The whole public answer. Adding a component here exposes it; keep it public-safe. */
    public record TrackResult(String reference, String title, StatusView status, Instant createdAt, Instant updatedAt,
                              List<TimelineEntry> timeline) {}

    private final PublishedDefinitions definitions;
    private final TrackingRepository repository;

    public TrackingService(PublishedDefinitions definitions, TrackingRepository repository) {
        this.definitions = definitions;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public TrackResult track(String appCode, String reference, String email) {
        validate(reference, email);
        String ref = trimWs(reference).toUpperCase(Locale.ROOT);
        String mail = trimWs(email).toLowerCase(Locale.ROOT);

        PublishedApp app = (appCode == null ? Optional.<PublishedApp>empty() : definitions.latestPublished(appCode))
                .orElseThrow(TrackingService::noMatch);
        TrackingRepository.Candidate candidate = repository.findCandidate(appCode, ref)
                .orElseThrow(TrackingService::noMatch);
        Entity entity = app.definition().entity(candidate.entityCode()).orElseThrow(TrackingService::noMatch);
        if (entity.tracking() == null
                || !repository.contactMatches(candidate.recordId(), entity.tracking().contactField(), mail)) {
            throw noMatch();
        }
        TrackingRepository.Snapshot snap = repository.snapshot(candidate.recordId(), entity.titleField())
                .orElseThrow(TrackingService::noMatch);

        Optional<Status> declared = definitions.publishedVersion(appCode, snap.version())
                .flatMap(p -> p.definition().workflow(snap.workflowCode()))
                .map(Workflow::statuses)
                .flatMap(list -> list.stream().filter(s -> s.label().equals(snap.status())).findFirst());
        StatusView status = new StatusView(snap.status(), declared.map(Status::color).orElse(null),
                declared.map(s -> s.category().name()).orElse(null));
        List<TimelineEntry> timeline = new ArrayList<>();
        repository.timeline(snap.instanceId()).forEach(t -> timeline.add(new TimelineEntry(t.at(), t.status())));
        String title = snap.title() == null ? null
                : snap.title().length() > TITLE_MAX ? snap.title().substring(0, TITLE_MAX) : snap.title();
        return new TrackResult(snap.referenceNo(), title, status, snap.createdAt(), snap.updatedAt(),
                List.copyOf(timeline));
    }

    /** Strips spaces, tabs and line breaks at both ends: the same set the SQL comparison strips (btrim). */
    public static String trimWs(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isWs(s.charAt(start))) {
            start++;
        }
        while (end > start && isWs(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /** The only failure answer for a well-formed request. */
    private static NotFound noMatch() {
        return new NotFound(NO_MATCH_MESSAGE);
    }

    private static void validate(String reference, String email) {
        List<ApiViolation> v = new ArrayList<>();
        if (reference == null || reference.isBlank()) {
            v.add(new ApiViolation("reference", "REQUIRED", "reference is required"));
        } else if (reference.trim().length() > REFERENCE_MAX) {
            v.add(new ApiViolation("reference", "MAX_LENGTH", "reference must be at most " + REFERENCE_MAX
                    + " characters"));
        }
        if (email == null || email.isBlank()) {
            v.add(new ApiViolation("email", "REQUIRED", "email is required"));
        } else if (email.trim().length() > EMAIL_MAX) {
            v.add(new ApiViolation("email", "MAX_LENGTH", "email must be at most " + EMAIL_MAX + " characters"));
        }
        if (!v.isEmpty()) {
            throw new ValidationFailed("Invalid tracking request", v);
        }
    }
}
