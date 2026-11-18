package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.app.TrackingService;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.config.TrackingWebConfig;
import dev.skeletonkey.definition.DefinitionReadService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.persistence.TrackingRepository;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.persistence.memory.InMemoryTrackingRepository;
import dev.skeletonkey.persistence.memory.InMemoryTrackingRepository.Row;
import dev.skeletonkey.persistence.memory.InMemoryTrackingRepository.Stored;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** The public track endpoint: shape, uniform 404, no login, no caching, validation and per-IP rate limiting. */
@SpringJUnitWebConfig(TrackEndpointTest.Cfg.class)
class TrackEndpointTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-track-secret";
    static final String REF = "GRV-2026-000123";
    static final String EMAIL = "Citizen@Example.org";

    /** Counts lookups so tests can prove a rate-limited request never reached the service. */
    static final AtomicInteger LOOKUPS = new AtomicInteger();

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, TrackController.class, DefinitionReadController.class, ApiExceptionHandler.class,
            TrackingWebConfig.class})
    static class Cfg implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override
        public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> cs) {
            for (var c : cs) {
                if (c instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter j) {
                    j.setObjectMapper(new ObjectMapper().registerModule(dev.skeletonkey.config.JacksonConfig.instantModule())
                            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
                }
            }
        }

        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean AdminGuard adminGuard(SkeletonProperties p) { return new AdminGuard(p); }
        @Bean ObjectMapper objectMapper() {
            return JsonMapper.builder().addModule(new JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        }
        @Bean InMemoryDefinitionRepository definitionRepository() { return new InMemoryDefinitionRepository(); }
        @Bean DefinitionService definitionService(InMemoryDefinitionRepository repo) {
            DefinitionParser parser = new DefinitionParser();
            return new DefinitionService(new DefinitionLoader(), repo, parser,
                    new DefinitionCache(parser), HandlerCatalog.empty(),
                    new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()), Clock.systemUTC());
        }
        @Bean DefinitionReadService definitionReadService(DefinitionService d, InMemoryDefinitionRepository r) {
            return new DefinitionReadService(d, r);
        }
        @Bean InMemoryTrackingRepository trackingRepository() { return new InMemoryTrackingRepository(); }
        @Bean TrackingService trackingService(DefinitionService d, InMemoryTrackingRepository repo) {
            TrackingRepository counting = new TrackingRepository() {
                @Override public java.util.Optional<Candidate> findCandidate(String a, String r) {
                    LOOKUPS.incrementAndGet();
                    return repo.findCandidate(a, r);
                }
                @Override public boolean contactMatches(UUID id, String f, String e) { return repo.contactMatches(id, f, e); }
                @Override public java.util.Optional<Snapshot> snapshot(UUID id, String t) { return repo.snapshot(id, t); }
                @Override public List<TimelineRow> timeline(UUID id) { return repo.timeline(id); }
            };
            return new TrackingService(d, counting);
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired DefinitionService definitions;
    @Autowired InMemoryTrackingRepository repo;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    MockMvc mvc;
    /** Each test uses its own client address so the shared limiter does not couple tests. */
    String ip;
    static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        ip = "203.0.113." + NEXT_IP.getAndIncrement();
        if (definitions.latestPublished("GRIEVANCE").isEmpty()) {
            String json = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
            int v = definitions.publish(definitions.submit(json).id(), DefinitionService.SYSTEM_ACTOR).version();
            Instant t0 = Instant.parse("2026-11-01T08:00:00Z");
            repo.add(new Stored(UUID.randomUUID(), "GRIEVANCE", REF, "GRIEVANCE",
                    Map.of("subject", "Broken street light", "contactEmail", EMAIL, "details", "SECRET-DETAILS",
                            "inspectionNotes", "SECRET-NOTES"),
                    t0, UUID.randomUUID(), v, "GRIEVANCE_FLOW", "Under review", t0.plusSeconds(7200),
                    List.of(new Row("START", null, "Received", t0, 1),
                            new Row("ACTION", "Received", "Received", t0.plusSeconds(60), 2),
                            new Row("EVENT_FAILED", "Received", "Rejected", t0.plusSeconds(61), 3),
                            new Row("ACTION", "Received", "Under review", t0.plusSeconds(7200), 4))));
            repo.add(new Stored(UUID.randomUUID(), "GRIEVANCE", "GRV-2026-000999", "GRIEVANCE",
                    Map.of("contactEmail", EMAIL), t0, null, v, "GRIEVANCE_FLOW", null, null, List.of()));
        }
    }

    private MockHttpServletRequestBuilder track(String app, Object body, String fromIp) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/public/" + app + "/track")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : mapper.writeValueAsString(body));
        b.with(r -> {
            r.setRemoteAddr(fromIp);
            return r;
        });
        return b;
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    @Test
    void matchingReferenceAndEmailGiveThePublicStatus() throws Exception {
        MockHttpServletResponse r = send(track("GRIEVANCE", Map.of("reference", " grv-2026-000123 ",
                "email", "  citizen@example.ORG "), ip));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode b = mapper.readTree(r.getContentAsString());
        assertThat(b.get("reference").asText()).isEqualTo(REF);
        assertThat(b.get("title").asText()).isEqualTo("Broken street light");
        assertThat(b.get("status").get("label").asText()).isEqualTo("Under review");
        assertThat(b.get("status").get("color").asText()).isEqualTo("#f59e0b");
        assertThat(b.get("status").get("category").asText()).isEqualTo("IN_PROGRESS");
        assertThat(b.get("createdAt").asText()).isEqualTo("2026-11-01T08:00:00.000Z");
        assertThat(b.get("updatedAt").asText()).isEqualTo("2026-11-01T10:00:00.000Z");
        List<String> timeline = new ArrayList<>();
        b.get("timeline").forEach(e -> timeline.add(e.get("status").asText()));
        assertThat(timeline).containsExactly("Received", "Under review");
        assertThat(r.getContentAsString()).doesNotContain("SECRET").doesNotContainIgnoringCase(EMAIL);
    }

    @Test
    void everyMismatchGivesTheSameResponse() throws Exception {
        List<MockHttpServletRequestBuilder> cases = List.of(
                track("GRIEVANCE", Map.of("reference", REF, "email", "other@example.org"), ip),
                track("GRIEVANCE", Map.of("reference", "GRV-2026-000124", "email", EMAIL), ip),
                track("NO_SUCH_APP", Map.of("reference", REF, "email", EMAIL), ip),
                track("GRIEVANCE", Map.of("reference", "GRV-2026-000999", "email", EMAIL), ip),
                track("GRIEVANCE", Map.of("reference", REF, "email", "%"), ip));
        List<String> answers = new ArrayList<>();
        for (MockHttpServletRequestBuilder c : cases) {
            MockHttpServletResponse r = send(c);
            assertThat(r.getStatus()).isEqualTo(404);
            JsonNode b = mapper.readTree(r.getContentAsString());
            ((com.fasterxml.jackson.databind.node.ObjectNode) b).remove("requestId");
            List<String> headers = new ArrayList<>(r.getHeaderNames());
            headers.remove("X-Request-Id");
            Collections.sort(headers);
            List<String> values = new ArrayList<>();
            for (String h : headers) {
                values.add(h + "=" + r.getHeaderValues(h));
            }
            answers.add(b + " " + values);
        }
        assertThat(answers).hasSize(5).allMatch(a -> a.equals(answers.get(0)));
        assertThat(answers.get(0)).contains(TrackingService.NO_MATCH_MESSAGE).contains("NOT_FOUND")
                .contains("Cache-Control=[no-store]");
    }

    @Test
    void worksWithoutLoginAndIgnoresAnyToken() throws Exception {
        Map<String, String> ok = Map.of("reference", REF, "email", EMAIL);
        assertThat(send(track("GRIEVANCE", ok, ip).header("Authorization", "Bearer garbage")).getStatus())
                .isEqualTo(200);
        String valid = "Bearer " + jwt.issue(new dev.skeletonkey.persistence.UserAccount(UUID.randomUUID(), "u", "x",
                "u", true, java.util.Set.of("CLERK")));
        assertThat(send(track("GRIEVANCE", ok, ip).header("Authorization", valid)).getStatus()).isEqualTo(200);
    }

    @Test
    void invalidRequestsAreRejectedWithoutRevealingAnything() throws Exception {
        MockHttpServletResponse blank = send(track("GRIEVANCE", Map.of("reference", " ", "email", ""), ip));
        assertThat(blank.getStatus()).isEqualTo(422);
        assertThat(blank.getContentAsString()).contains("reference").contains("email");
        assertThat(send(track("GRIEVANCE", Map.of("reference", "R".repeat(129), "email", EMAIL), ip)).getStatus())
                .isEqualTo(422);
        assertThat(send(track("GRIEVANCE", "{not json", ip)).getStatus()).isEqualTo(400);
        assertThat(send(track("GRIEVANCE", "", ip)).getStatus()).isEqualTo(422);
    }

    @Test
    void theEleventhRequestInAMinuteIsLimitedAndNeverLooksUp() throws Exception {
        for (int i = 0; i < 9; i++) {
            assertThat(send(track("GRIEVANCE", Map.of("reference", REF, "email", "x" + i + "@e.org"), ip))
                    .getStatus()).isEqualTo(404);
        }
        // Malformed bodies count too.
        assertThat(send(track("GRIEVANCE", "{bad", ip)).getStatus()).isEqualTo(400);
        int before = LOOKUPS.get();
        MockHttpServletResponse limited = send(track("GRIEVANCE", Map.of("reference", REF, "email", EMAIL), ip));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode b = mapper.readTree(limited.getContentAsString());
        assertThat(b.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(b.get("status").asInt()).isEqualTo(429);
        assertThat(LOOKUPS.get()).isEqualTo(before);
        // Another address is not affected, and other endpoints are not limited.
        assertThat(send(track("GRIEVANCE", Map.of("reference", REF, "email", EMAIL), ip + "0")).getStatus())
                .isEqualTo(200);
        for (int i = 0; i < 15; i++) {
            MockHttpServletResponse pub = mvc.perform(get("/api/public/apps/GRIEVANCE").with(r -> {
                r.setRemoteAddr(ip);
                return r;
            })).andReturn().getResponse();
            assertThat(pub.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void xRealIpIsTrustedOnlyFromATrustedProxy() throws Exception {
        // From an untrusted peer, rotating X-Real-IP does not escape the limit.
        for (int i = 0; i < 10; i++) {
            send(track("GRIEVANCE", Map.of("reference", REF, "email", "a@b.org"), ip)
                    .header("X-Real-IP", "198.51.100." + i));
        }
        assertThat(send(track("GRIEVANCE", Map.of("reference", REF, "email", "a@b.org"), ip)
                .header("X-Real-IP", "198.51.100.200")).getStatus()).isEqualTo(429);

        // Behind the proxy (private peer), each real client has its own bucket.
        String proxy = "172.18.0." + (NEXT_IP.get() % 250 + 2);
        String client = "192.0.2." + (NEXT_IP.getAndIncrement() % 250 + 1);
        for (int i = 0; i < 10; i++) {
            assertThat(send(track("GRIEVANCE", Map.of("reference", REF, "email", "a@b.org"), proxy)
                    .header("X-Real-IP", client)).getStatus()).isEqualTo(404);
        }
        assertThat(send(track("GRIEVANCE", Map.of("reference", REF, "email", "a@b.org"), proxy)
                .header("X-Real-IP", client)).getStatus()).isEqualTo(429);
        assertThat(send(track("GRIEVANCE", Map.of("reference", REF, "email", "a@b.org"), proxy)
                .header("X-Real-IP", client + "9")).getStatus()).isNotEqualTo(429);
    }

    @Test
    void publicSubsetFlagsTrackedFormsWithoutTheContactField() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/public/apps/GRIEVANCE").with(x -> {
            x.setRemoteAddr(ip);
            return x;
        })).andReturn().getResponse();
        JsonNode form = mapper.readTree(r.getContentAsString()).get("forms").get(0);
        assertThat(form.get("trackingEnabled").asBoolean()).isTrue();
        assertThat(r.getContentAsString()).doesNotContain("contactField");
    }
}
