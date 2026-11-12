package dev.skeletonkey.testing;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Workflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Generators for sequences of action attempts against a workflow of a definition. An attempt names an action
 * code that exists somewhere in the workflow, so it may or may not be available at the current step; the
 * test decides which attempts apply. {@code pick} lets a test choose among the actions available at the
 * current step ({@code pick % available.size()}), which makes sequences that mostly make progress.
 */
public final class ActionSequenceArbitraries {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ActionSequenceArbitraries() {}

    /**
     * One attempt.
     *
     * @param actor          the caller
     * @param actionCode     an action code of the workflow, or an unknown code for negative cases
     * @param pick           non-negative index for choosing among the currently available actions
     * @param dataChanges    field changes, typed to the entity's field types
     * @param remarks        remarks text, may be null
     * @param hasAttachment  whether an attachment is supplied
     */
    public record ActionAttempt(Principal actor, String actionCode, int pick, ObjectNode dataChanges,
                                String remarks, boolean hasAttachment) {}

    /** Sequences of 0 to {@code maxLength} attempts against the first workflow of the definition. */
    public static Arbitrary<List<ActionAttempt>> sequences(AppDefinition def, int maxLength) {
        return sequences(def, def.workflows().get(0), maxLength);
    }

    public static Arbitrary<List<ActionAttempt>> sequences(AppDefinition def, Workflow wf, int maxLength) {
        return attempt(def, wf).list().ofMinSize(0).ofMaxSize(maxLength);
    }

    public static Arbitrary<ActionAttempt> attempt(AppDefinition def, Workflow wf) {
        List<String> codes = new ArrayList<>();
        for (Step s : wf.steps()) {
            s.actions().forEach(a -> codes.add(a.code()));
        }
        codes.add("UNKNOWN_ACTION");
        Entity entity = def.entity(wf.entity()).orElseThrow();
        return Combinators.combine(
                        PrincipalArbitraries.principal(def),
                        Arbitraries.of(codes),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.longs(),
                        Arbitraries.strings().alpha().ofMaxLength(20).injectNull(0.3),
                        Arbitraries.of(true, false))
                .as((actor, code, pick, seed, remarks, attachment) ->
                        new ActionAttempt(actor, code, pick, changes(entity, new Random(seed)), remarks, attachment));
    }

    private static ObjectNode changes(Entity entity, Random rnd) {
        ObjectNode out = F.objectNode();
        for (Field f : entity.fields()) {
            if (rnd.nextInt(4) != 0) {
                continue;
            }
            switch (f.type()) {
                case TEXT, TEXTAREA, EMAIL, PHONE -> out.put(f.code(), "t" + rnd.nextInt(100));
                case NUMBER -> out.put(f.code(), rnd.nextInt(100));
                case DECIMAL -> out.put(f.code(), rnd.nextInt(10000) / 100.0);
                case BOOLEAN -> out.put(f.code(), rnd.nextBoolean());
                case SELECT -> out.put(f.code(), "opt1");
                case DATE -> out.put(f.code(), "2026-01-" + String.format("%02d", 1 + rnd.nextInt(28)));
                default -> { }
            }
        }
        return out;
    }
}
