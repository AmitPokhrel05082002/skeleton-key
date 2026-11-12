package dev.skeletonkey.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefinitionParserTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DefinitionParser parser = new DefinitionParser();

    private static JsonNode example() throws Exception {
        return MAPPER.readTree(Files.readString(Path.of("../../examples/grievance.app.json")));
    }

    @Test
    void parsesGrievanceExample() throws Exception {
        AppDefinition def = parser.parse(example());
        assertThat(def.app().code()).isEqualTo("GRIEVANCE");
        assertThat(def.publicRole()).map(Role::code).contains("PUBLIC");
        Workflow wf = def.workflow("GRIEVANCE_FLOW").orElseThrow();
        assertThat(wf.statuses()).hasSize(5);
        assertThat(wf.initialStatus()).isEqualTo("Received");
        Step triage = wf.step("TRIAGE").orElseThrow();
        assertThat(triage.assignee().strategy()).isEqualTo(Assignee.Strategy.LEAST_LOADED);
        assertThat(triage.fields()).containsEntry("category", Step.FieldRule.REQUIRED);
        assertThat(triage.onExit().get(0)).isInstanceOf(Event.Notify.class);
        Action reject = triage.action("REJECT").orElseThrow();
        assertThat(reject.style()).isEqualTo(Action.Style.DANGER);
        assertThat(reject.remarks()).isEqualTo(Action.Presence.REQUIRED);
        assertThat(reject.attachment()).isEqualTo(Action.Presence.HIDDEN);
        Transition t = wf.transitions().get(0);
        assertThat(t.status()).isEqualTo("Inspection");
        assertThat(t.priority()).isEqualTo(10);
        assertThat(wf.step("END_RESOLVED").orElseThrow().outcome()).isEqualTo("Resolved");
        assertThat(wf.step("SITE_VISIT").orElseThrow().skipWhen()).isNotNull();
    }

    @Test
    void modelIsImmutable() throws Exception {
        AppDefinition def = parser.parse(example());
        assertThatThrownBy(() -> def.roles().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingRequiredProperty() throws Exception {
        JsonNode bad = example().deepCopy();
        ((ObjectNode) bad).remove("roles");
        assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(DefinitionParseException.class);
    }

    @Test
    void cacheParsesOncePerAppAndVersion() throws Exception {
        DefinitionCache cache = new DefinitionCache(parser);
        JsonNode doc = example();
        AtomicInteger loads = new AtomicInteger();
        AppDefinition a = cache.get("GRIEVANCE", 1, () -> { loads.incrementAndGet(); return doc; });
        AppDefinition b = cache.get("GRIEVANCE", 1, () -> { loads.incrementAndGet(); return doc; });
        cache.get("GRIEVANCE", 2, () -> { loads.incrementAndGet(); return doc; });
        assertThat(a).isSameAs(b);
        assertThat(loads.get()).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(2);
    }
}
