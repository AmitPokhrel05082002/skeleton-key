package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record Action(String code, String label, List<String> roles, String to, String status, Presence remarks,
                     Presence attachment, Style style, String confirm, JsonNode when, String handler) {
    public enum Presence { HIDDEN, OPTIONAL, REQUIRED }
    public enum Style { PRIMARY, DEFAULT, DANGER }

    public Action {
        roles = List.copyOf(roles);
        remarks = remarks == null ? Presence.OPTIONAL : remarks;
        attachment = attachment == null ? Presence.HIDDEN : attachment;
        style = style == null ? Style.DEFAULT : style;
    }
}
