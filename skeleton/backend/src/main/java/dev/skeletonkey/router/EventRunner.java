package dev.skeletonkey.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.expr.ExpressionException;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Event;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.plugin.HandlerContext;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs step events (onEnter / onExit) sequentially in declared order (Req 14). Pure: works on a copy of the data and
 * returns the new data plus history entries. A failing event never stops the following ones.
 */
public class EventRunner {

    public static final String EVENT_FAILED = "EVENT_FAILED";
    public static final String EVENT_DEFERRED = "EVENT_DEFERRED";

    /** New record data and the EVENT_FAILED / EVENT_DEFERRED entries, in event order. */
    public record Result(Map<String, Object> data, List<HistoryEntry> history) {
        public Result {
            data = new LinkedHashMap<>(data);
            history = List.copyOf(history);
        }
    }

    private final ExpressionEngine engine;
    private final HandlerRegistry handlers;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventRunner(ExpressionEngine engine, HandlerRegistry handlers) {
        this.engine = engine;
        this.handlers = handlers;
    }

    /**
     * @param ctx supplies the {@code instance} and {@code user} roots; the {@code data} root is rebuilt before every
     *            event so later events see earlier changes
     * @param actionCode the action being executed, or null when not triggered by an action
     */
    public Result run(List<Event> events, Entity entity, Map<String, Object> data, InstanceState inst,
                      Principal principal, String actionCode, EvalContext ctx, Instant now) {
        Map<String, Object> current = new LinkedHashMap<>(data);
        List<HistoryEntry> history = new ArrayList<>();
        if (events == null) {
            return new Result(current, history);
        }
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            EvalContext evalCtx = new EvalContext(mapper.valueToTree(current), ctx.instance(), ctx.user());
            if (event.when() != null) {
                try {
                    if (!engine.evaluateBoolean(event.when(), evalCtx)) {
                        continue;
                    }
                } catch (ExpressionException e) {
                    history.add(entry(EVENT_FAILED, inst, principal, now, i, event, null, "when: " + e.getMessage()));
                    continue;
                }
            }
            switch (event) {
                case Event.SetField sf -> setField(sf, i, entity, current, evalCtx, inst, principal, now, history);
                case Event.HandlerCall hc ->
                        callHandler(hc, i, entity, current, inst, principal, actionCode, now, history);
                case Event.Notify n -> history.add(entry(EVENT_DEFERRED, inst, principal, now, i, event, null, null));
                case Event.Webhook w -> history.add(entry(EVENT_DEFERRED, inst, principal, now, i, event, null, null));
            }
        }
        return new Result(current, history);
    }

    private void setField(Event.SetField sf, int index, Entity entity, Map<String, Object> current,
                          EvalContext evalCtx, InstanceState inst, Principal principal, Instant now,
                          List<HistoryEntry> history) {
        if (entity.field(sf.field()).isEmpty()) {
            history.add(entry(EVENT_FAILED, inst, principal, now, index, sf, null,
                    "Unknown field '" + sf.field() + "'"));
            return;
        }
        JsonNode value = sf.value();
        Object result;
        if (value != null && value.isObject()) {
            try {
                result = engine.evaluate(value, evalCtx);
            } catch (ExpressionException e) {
                history.add(entry(EVENT_FAILED, inst, principal, now, index, sf, null, e.getMessage()));
                return;
            }
        } else {
            result = value == null || value.isNull() ? null : mapper.convertValue(value, Object.class);
        }
        current.put(sf.field(), result);
    }

    private void callHandler(Event.HandlerCall hc, int index, Entity entity, Map<String, Object> current,
                             InstanceState inst, Principal principal, String actionCode, Instant now,
                             List<HistoryEntry> history) {
        PluginHandler handler = handlers.find(hc.handler()).orElse(null);
        if (handler == null) {
            history.add(entry(EVENT_FAILED, inst, principal, now, index, hc, hc.handler(),
                    "Handler '" + hc.handler() + "' is not registered"));
            return;
        }
        HandlerResult result;
        try {
            result = handler.handle(new HandlerContext(current, inst, principal, actionCode));
        } catch (RuntimeException e) {
            history.add(entry(EVENT_FAILED, inst, principal, now, index, hc, hc.handler(), String.valueOf(e)));
            return;
        }
        if (result == null || result.fieldChanges().isEmpty()) {
            return;
        }
        List<String> unknown = result.fieldChanges().keySet().stream()
                .filter(f -> entity.field(f).isEmpty()).toList();
        if (!unknown.isEmpty()) {
            history.add(entry(EVENT_FAILED, inst, principal, now, index, hc, hc.handler(),
                    "Handler returned unknown field(s) " + unknown));
            return;
        }
        current.putAll(result.fieldChanges());
    }

    private HistoryEntry entry(String kind, InstanceState inst, Principal principal, Instant now, int index,
                               Event event, String handlerName, String failure) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("index", index);
        detail.put("event", eventType(event));
        if (event instanceof Event.SetField sf) {
            detail.put("field", sf.field());
        }
        if (event instanceof Event.Webhook w) {
            detail.put("url", w.url());
        }
        if (event instanceof Event.Notify n) {
            detail.put("template", n.template());
        }
        if (handlerName != null) {
            detail.put("handler", handlerName);
        }
        if (failure != null) {
            detail.put("error", failure);
        }
        return new HistoryEntry(kind, null, inst.step(), inst.step(), inst.status(), inst.status(),
                principal == null ? null : principal.userId(), null, null, detail, now, 0L);
    }

    private static String eventType(Event event) {
        return switch (event) {
            case Event.SetField s -> "SET_FIELD";
            case Event.HandlerCall h -> "HANDLER";
            case Event.Notify n -> "NOTIFY";
            case Event.Webhook w -> "WEBHOOK";
        };
    }
}
