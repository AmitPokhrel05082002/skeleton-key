package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Step lifecycle event (onEnter / onExit). when is an optional JSONLogic expression (null when absent). */
public sealed interface Event permits Event.Notify, Event.SetField, Event.Webhook, Event.HandlerCall {
    JsonNode when();

    record Notify(JsonNode when, String template, List<String> to) implements Event {
        public Notify { to = List.copyOf(to); }
    }

    record SetField(JsonNode when, String field, JsonNode value) implements Event {}

    record Webhook(JsonNode when, String url) implements Event {}

    record HandlerCall(JsonNode when, String handler) implements Event {}
}
