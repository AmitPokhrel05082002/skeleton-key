package dev.skeletonkey.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Event;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventRunnerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, PluginHandler> beans = new LinkedHashMap<>();
    private final EventRunner runner;
    private final Entity entity = new Entity("TICKET", "Ticket", null, null, List.of(field("a"), field("b")));
    private final InstanceState inst = new InstanceState(UUID.randomUUID(), UUID.randomUUID(), "APP", 1, "WF",
            "S1", "Open", null, null, false, NOW, 0L, null, NOW);
    private final Principal user = new Principal(UUID.randomUUID(), Set.of("AGENT"));

    EventRunnerTest() {
        beans.put("bump", ctx -> {
            calls.incrementAndGet();
            return HandlerResult.of(Map.of("b", "from-handler"));
        });
        beans.put("boom", ctx -> { throw new IllegalStateException("bad"); });
        beans.put("badField", ctx -> HandlerResult.of(Map.of("nope", 1)));
        runner = new EventRunner(new ExpressionEngine(), new HandlerRegistry(beans));
    }

    private static Field field(String code) {
        return new Field(code, code, Field.Type.TEXT, false, null, null, List.of(), null, null, null, null, null,
                null, null);
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private EventRunner.Result run(Map<String, Object> data, Event... events) {
        EvalContext ctx = new EvalContext(null, json("{\"status\":\"Open\",\"step\":\"S1\"}"), json("{}"));
        return runner.run(List.of(events), entity, data, inst, user, "GO", ctx, NOW);
    }

    @Test
    void setFieldLiteralAndExpressionRunInOrder() {
        EventRunner.Result r = run(Map.of("a", "x"),
                new Event.SetField(null, "b", json("\"lit\"")),
                new Event.SetField(null, "a", json("{\"cat\":[{\"var\":\"data.b\"},\"!\"]}")));
        assertThat(r.data()).containsEntry("b", "lit").containsEntry("a", "lit!");
        assertThat(r.history()).isEmpty();
    }

    @Test
    void whenFalseSkipsAndWhenErrorWritesEventFailedThenContinues() {
        EventRunner.Result r = run(Map.of(),
                new Event.SetField(json("false"), "a", json("\"no\"")),
                new Event.SetField(json("{\"var\":\"data.a\"}"), "a", json("\"non-boolean\"")),
                new Event.SetField(null, "b", json("\"yes\"")));
        assertThat(r.data()).doesNotContainKey("a").containsEntry("b", "yes");
        assertThat(r.history()).hasSize(1);
        assertThat(r.history().get(0).kind()).isEqualTo("EVENT_FAILED");
    }

    @Test
    void setFieldUnknownFieldOrFailingExpressionLeavesDataUnchanged() {
        EventRunner.Result r = run(Map.of("a", "keep"),
                new Event.SetField(null, "ghost", json("1")),
                new Event.SetField(null, "a", json("{\"nosuchop\":[1]}")),
                new Event.SetField(null, "b", json("\"after\"")));
        assertThat(r.data()).containsEntry("a", "keep").containsEntry("b", "after");
        assertThat(r.history()).extracting("kind").containsExactly("EVENT_FAILED", "EVENT_FAILED");
    }

    @Test
    void handlerInvokedOnceAndChangesApplied() {
        EventRunner.Result r = run(Map.of(), new Event.HandlerCall(null, "bump"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(r.data()).containsEntry("b", "from-handler");
    }

    @Test
    void missingFailingOrBadHandlerWritesEventFailedAndContinues() {
        EventRunner.Result r = run(Map.of(),
                new Event.HandlerCall(null, "unknown"),
                new Event.HandlerCall(null, "boom"),
                new Event.HandlerCall(null, "badField"),
                new Event.SetField(null, "a", json("\"done\"")));
        assertThat(r.history()).extracting("kind").containsExactly("EVENT_FAILED", "EVENT_FAILED", "EVENT_FAILED");
        assertThat(r.history().get(0).detail().get("handler").asText()).isEqualTo("unknown");
        assertThat(r.data()).containsEntry("a", "done").doesNotContainKey("nope");
    }

    @Test
    void notifyAndWebhookAreDeferredNotDelivered() {
        EventRunner.Result r = run(Map.of(),
                new Event.Notify(null, "t", List.of("INITIATOR")),
                new Event.Webhook(null, "https://example.com/hook"));
        assertThat(r.history()).extracting("kind").containsExactly("EVENT_DEFERRED", "EVENT_DEFERRED");
        assertThat(r.history().get(1).detail().get("event").asText()).isEqualTo("WEBHOOK");
    }

    @Test
    void registryResolvesByNameAndActsAsCatalog() {
        HandlerRegistry reg = new HandlerRegistry(beans);
        assertThat(reg.contains("bump")).isTrue();
        assertThat(reg.contains("Bump")).isFalse();
        assertThat(reg.find("boom")).isPresent();
        assertThat(reg.find("zzz")).isEmpty();
    }
}
