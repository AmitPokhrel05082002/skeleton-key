package dev.skeletonkey.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.persistence.memory.InMemoryTrackingRepository;
import dev.skeletonkey.persistence.memory.InMemoryTrackingRepository.Row;
import dev.skeletonkey.persistence.memory.InMemoryTrackingRepository.Stored;
import dev.skeletonkey.web.NotFound;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Tracking service properties (design Properties 1, 2, 3 and 8) against the published grievance example and an
 * in-memory repository with the same matching rules as the SQL.
 */
class TrackingServicePropertyTest {

    static final String APP = "GRIEVANCE";
    static final String WORKFLOW = "GRIEVANCE_FLOW";
    static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private final InMemoryTrackingRepository repo = new InMemoryTrackingRepository();
    private final DefinitionService definitions;
    private final TrackingService service;
    private int version;

    TrackingServicePropertyTest() throws Exception {
        DefinitionParser parser = new DefinitionParser();
        definitions = new DefinitionService(new DefinitionLoader(), new InMemoryDefinitionRepository(), parser,
                new DefinitionCache(parser), HandlerCatalog.empty(),
                new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()), Clock.systemUTC());
        String json = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
        version = definitions.publish(definitions.submit(json).id(), DefinitionService.SYSTEM_ACTOR).version();
        service = new TrackingService(definitions, repo);
    }

    private Stored store(String reference, String email, Map<String, Object> extra, List<Row> history,
                         String status) {
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("subject", "Broken light");
        if (email != null) {
            data.put("contactEmail", email);
        }
        Stored s = new Stored(UUID.randomUUID(), APP, reference, "GRIEVANCE", data,
                Instant.parse("2026-11-01T08:00:00Z"), UUID.randomUUID(), version, WORKFLOW, status,
                Instant.parse("2026-11-03T09:00:00Z"), history);
        repo.add(s);
        return s;
    }

    // ---- generators ---------------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().withChars("abcxyz019._%+-").ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> domain = Arbitraries.strings().withChars("abcdefxyz").ofMinLength(1).ofMaxLength(8);
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d + ".org");
    }

    @Provide
    Arbitrary<String> probes() {
        return Arbitraries.oneOf(emails(), Arbitraries.of("%", "_", "%@%", ".*", "' OR '1'='1", "*", "\\"),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(40).filter(s -> !s.isBlank()));
    }

    @Provide
    Arbitrary<String> references() {
        return Arbitraries.integers().between(1, 999_999).map(n -> String.format("GRV-2026-%06d", n));
    }

    // ---- Property 1: a mismatched email never returns data ------------------------------------------------

    @Property(tries = 300)
    void mismatchedEmailNeverReturnsData(@ForAll("references") String ref, @ForAll("emails") String stored,
                                         @ForAll("probes") String probe) {
        repo.clear();
        store(ref, stored, Map.of(), List.of(), "Received");
        boolean same = TrackingService.trimWs(probe).toLowerCase(Locale.ROOT)
                .equals(stored.toLowerCase(Locale.ROOT));
        if (same) {
            assertThat(service.track(APP, ref, probe).reference()).isEqualTo(ref);
        } else {
            assertThatThrownBy(() -> service.track(APP, ref, probe)).isInstanceOf(NotFound.class)
                    .hasMessage(TrackingService.NO_MATCH_MESSAGE);
        }
        // The stored email with any one character changed never matches.
        for (int i = 0; i < stored.length(); i++) {
            char c = stored.charAt(i);
            char other = Character.toLowerCase(c) == 'q' ? 'w' : 'q';
            String mutated = stored.substring(0, i) + other + stored.substring(i + 1);
            assertThatThrownBy(() -> service.track(APP, ref, mutated)).isInstanceOf(NotFound.class);
        }
    }

    // ---- Property 2: all failure causes look the same -----------------------------------------------------

    @Property(tries = 100)
    void everyFailureCauseGivesTheSameAnswer(@ForAll("references") String ref, @ForAll("emails") String email) {
        repo.clear();
        store(ref, email, Map.of(), List.of(), "Received");
        String other = ref.equals("GRV-2026-000001") ? "GRV-2026-000002" : "GRV-2026-000001";
        // Record without instance, and record of an entity without tracking (entity unknown to the definition).
        String noInstanceRef = "GRV-2025-000001";
        repo.add(new Stored(UUID.randomUUID(), APP, noInstanceRef, "GRIEVANCE",
                Map.of("contactEmail", email), Instant.now(), null, version, WORKFLOW, null, null, List.of()));
        String foreignRef = "XYZ-2026-000001";
        repo.add(new Stored(UUID.randomUUID(), APP, foreignRef, "UNKNOWN_ENTITY",
                Map.of("contactEmail", email), Instant.now(), UUID.randomUUID(), version, WORKFLOW, "Received",
                Instant.now(), List.of()));

        List<Runnable> causes = List.of(
                () -> service.track("NO_SUCH_APP", ref, email),       // unknown / unpublished app
                () -> service.track(APP, other, email),               // no such reference
                () -> service.track(APP, ref, "x" + email),           // email differs
                () -> service.track(APP, noInstanceRef, email),       // no workflow instance
                () -> service.track(APP, foreignRef, email));         // tracking not enabled for the entity
        Set<String> answers = new TreeSet<>();
        for (Runnable cause : causes) {
            try {
                cause.run();
                throw new AssertionError("expected a 404");
            } catch (NotFound e) {
                answers.add(e.getClass().getName() + "|" + e.getMessage());
            }
        }
        assertThat(answers).containsExactly(NotFound.class.getName() + "|" + TrackingService.NO_MATCH_MESSAGE);
    }

    // ---- Property 3: case and surrounding whitespace are ignored ------------------------------------------

    @Property(tries = 200)
    void emailCaseAndPaddingAndReferenceCaseAreIgnored(@ForAll("references") String ref,
                                                       @ForAll("emails") String stored,
                                                       @ForAll @IntRange(min = 0, max = 3) int pad,
                                                       @ForAll long caseSeed) {
        repo.clear();
        store(ref, stored, Map.of(), List.of(), "Received");
        java.util.Random rnd = new java.util.Random(caseSeed);
        StringBuilder recased = new StringBuilder();
        for (char c : stored.toCharArray()) {
            recased.append(rnd.nextBoolean() ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        String padded = " ".repeat(pad) + recased + "\t".repeat(pad);
        assertThat(service.track(APP, ref, padded).reference()).isEqualTo(ref);
        assertThat(service.track(APP, "  " + ref.toLowerCase(Locale.ROOT) + " ", stored).reference()).isEqualTo(ref);
    }

    // ---- Property 8: the response contains only public-safe data ------------------------------------------

    private static final List<String> KINDS =
            List.of("START", "ACTION", "SKIPPED", "AUTO", "EVENT_DEFERRED", "EVENT_FAILED", "EXPRESSION_ERROR");
    private static final List<String> LABELS =
            List.of("Received", "Under review", "Inspection", "Resolved", "Rejected");

    @Provide
    Arbitrary<List<Row>> histories() {
        Arbitrary<Row> row = Combinators.combine(Arbitraries.of(KINDS), Arbitraries.of(LABELS).injectNull(0.2),
                        Arbitraries.of(LABELS).injectNull(0.2), Arbitraries.integers().between(0, 10_000))
                .as((k, from, to, t) -> new Row(k, from, to, Instant.parse("2026-11-01T08:00:00Z").plusSeconds(t), 0));
        return row.list().ofMaxSize(15).map(rows -> {
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                out.add(new Row(r.kind(), r.fromStatus(), r.toStatus(), r.at(), i));
            }
            return out;
        });
    }

    @Property(tries = 200)
    void responseHoldsOnlyPublicSafeData(@ForAll("references") String ref, @ForAll("emails") String email,
                                         @ForAll("histories") List<Row> history,
                                         @ForAll("secrets") Map<String, String> secrets) throws Exception {
        repo.clear();
        Map<String, Object> extra = new HashMap<>(secrets);
        store(ref, email, extra, history, "Under review");
        TrackingService.TrackResult result = service.track(APP, ref, email);
        JsonNode json = JSON.valueToTree(result);

        assertThat(names(json)).containsExactlyInAnyOrder("reference", "title", "status", "createdAt", "updatedAt",
                "timeline");
        assertThat(names(json.get("status"))).containsExactlyInAnyOrder("label", "color", "category");
        json.get("timeline").forEach(e -> assertThat(names(e)).containsExactlyInAnyOrder("at", "status"));

        List<String> expected = history.stream()
                .filter(h -> h.toStatus() != null && (h.kind().equals("START")
                        || (List.of("ACTION", "SKIPPED", "AUTO").contains(h.kind())
                                && !h.toStatus().equals(h.fromStatus()))))
                .sorted(java.util.Comparator.comparing(Row::at).thenComparingLong(Row::seq))
                .map(Row::toStatus).toList();
        assertThat(result.timeline().stream().map(TrackingService.TimelineEntry::status).toList())
                .isEqualTo(expected);

        String text = JSON.writeValueAsString(result);
        assertThat(text).doesNotContainIgnoringCase(email);
        secrets.values().forEach(s -> assertThat(text).doesNotContain(s));
        assertThat(result.status().category()).isEqualTo("IN_PROGRESS");
    }

    @Provide
    Arbitrary<Map<String, String>> secrets() {
        Arbitrary<String> secret = Arbitraries.strings().withChars("QJZKVX").ofLength(12).map(s -> "SECRET" + s);
        return Arbitraries.maps(Arbitraries.of("details", "inspectionNotes", "resolutionNotes", "remarks",
                "assigneeName", "actor"), secret).ofMaxSize(6);
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }
}
