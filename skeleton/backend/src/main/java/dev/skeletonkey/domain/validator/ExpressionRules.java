package dev.skeletonkey.domain.validator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.domain.expr.ExprAnalysis;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Event;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Req 4.1-4.8: every expression (skipWhen, action when, transition when, event when) parses with
 * supported operators and valid var roots and fields; handlers exist; SET_FIELD targets exist.
 * Violation paths are JSON Pointers into the definition document.
 */
final class ExpressionRules implements RuleGroup {

    static final String EXPR_PARSE = "EXPR_PARSE";
    static final String EXPR_OPERATOR = "EXPR_OPERATOR";
    static final String EXPR_VAR_ROOT = "EXPR_VAR_ROOT";
    static final String EXPR_FIELD = "EXPR_FIELD";
    static final String UNKNOWN_HANDLER = "UNKNOWN_HANDLER";
    static final String SET_FIELD_UNKNOWN = "SET_FIELD_UNKNOWN";

    private final ExpressionEngine engine = new ExpressionEngine();
    private final HandlerCatalog handlers;

    ExpressionRules(HandlerCatalog handlers) {
        this.handlers = handlers == null ? HandlerCatalog.empty() : handlers;
    }

    @Override
    public void check(AppDefinition def, Consumer<Violation> out) {
        List<Workflow> workflows = def.workflows();
        for (int w = 0; w < workflows.size(); w++) {
            Workflow wf = workflows.get(w);
            String wfPtr = "/workflows/" + w;
            Optional<Entity> entity = def.entity(wf.entity());

            for (int s = 0; s < wf.steps().size(); s++) {
                checkStep(wf.steps().get(s), wfPtr + "/steps/" + s, entity, out);
            }
            for (int t = 0; t < wf.transitions().size(); t++) {
                Transition tr = wf.transitions().get(t);
                expression(tr.when(), wfPtr + "/transitions/" + t + "/when", entity, out);
            }
        }
    }

    private void checkStep(Step step, String ptr, Optional<Entity> entity, Consumer<Violation> out) {
        expression(step.skipWhen(), ptr + "/skipWhen", entity, out);
        handler(step.handler(), ptr + "/handler", out);
        for (int a = 0; a < step.actions().size(); a++) {
            Action action = step.actions().get(a);
            String aPtr = ptr + "/actions/" + a;
            expression(action.when(), aPtr + "/when", entity, out);
            handler(action.handler(), aPtr + "/handler", out);
        }
        events(step.onEnter(), ptr + "/onEnter", entity, out);
        events(step.onExit(), ptr + "/onExit", entity, out);
    }

    private void events(List<Event> events, String ptr, Optional<Entity> entity, Consumer<Violation> out) {
        for (int e = 0; e < events.size(); e++) {
            Event event = events.get(e);
            String ePtr = ptr + "/" + e;
            expression(event.when(), ePtr + "/when", entity, out);
            if (event instanceof Event.HandlerCall call) {
                handler(call.handler(), ePtr + "/handler", out);
            } else if (event instanceof Event.SetField set && entity.isPresent()
                    && entity.get().field(set.field()).isEmpty()) {
                out.accept(new Violation(SET_FIELD_UNKNOWN, ePtr,
                        "SET_FIELD event names field " + set.field() + " which does not exist on entity "
                                + entity.get().code()));
            }
        }
    }

    private void handler(String name, String ptr, Consumer<Violation> out) {
        if (name != null && !handlers.contains(name)) {
            out.accept(new Violation(UNKNOWN_HANDLER, ptr,
                    "Handler " + name + " is not a registered plugin handler"));
        }
    }

    private void expression(JsonNode expr, String ptr, Optional<Entity> entity, Consumer<Violation> out) {
        if (expr == null) {
            return;
        }
        ExprAnalysis analysis = engine.analyze(expr);
        for (ExprAnalysis.ParseError error : analysis.parseErrors()) {
            out.accept(new Violation(EXPR_PARSE, ptr + error.pointer(),
                    "Expression parse failure: " + error.message()));
        }
        for (ExprAnalysis.OperatorUse op : analysis.unsupportedOperators()) {
            out.accept(new Violation(EXPR_OPERATOR, ptr + op.pointer(),
                    "Unsupported operator " + op.name()));
        }
        for (ExprAnalysis.VarRef ref : analysis.invalidRootVars()) {
            out.accept(new Violation(EXPR_VAR_ROOT, ptr + ref.pointer(),
                    "var path " + ref.path() + " starts with " + ref.root()
                            + "; the first segment must be data, instance or user"));
        }
        if (entity.isEmpty()) {
            return;
        }
        for (ExprAnalysis.VarRef ref : analysis.varRefs()) {
            String field = ref.dataField();
            // data.$childOutcome and other $-prefixed keys are engine-provided, not entity fields.
            if (field != null && !field.startsWith("$") && entity.get().field(field).isEmpty()) {
                out.accept(new Violation(EXPR_FIELD, ptr + ref.pointer(),
                        "var path " + ref.path() + " names field " + field
                                + " which does not exist on entity " + entity.get().code()));
            }
        }
    }
}
