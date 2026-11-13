package dev.skeletonkey.app;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Form;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.record.RecordService;
import dev.skeletonkey.record.RecordView;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Form submission (Req 6.5, 6.6, 8.1, 11.2). Decides access from the form definition, accepts only the form's own
 * fields, then either starts the workflow (record and instance in one transaction, via {@link WorkflowService}) or
 * just creates the record. The result carries only the reference number and ids, never record data.
 */
@Service
public class FormSubmissionService {

    /** What a submitter gets back: no record data, no internals. {@code instanceId} is null without a workflow. */
    public record Result(UUID recordId, String referenceNo, UUID instanceId) {}

    private final PublishedDefinitions definitions;
    private final WorkflowService workflows;
    private final RecordService records;

    public FormSubmissionService(PublishedDefinitions definitions, WorkflowService workflows, RecordService records) {
        this.definitions = definitions;
        this.workflows = workflows;
        this.records = records;
    }

    /** @param caller the authenticated Principal, or null when the request carried no valid JWT */
    public Result submit(String appCode, String formCode, Map<String, Object> payload, Principal caller) {
        PublishedApp app = definitions.latestPublished(appCode).orElseThrow(
                () -> new Conflict("APP_NOT_PUBLISHED", "App '" + appCode + "' has no published version"));
        AppDefinition def = app.definition();
        Form form = def.form(formCode).orElseThrow(() -> new NotFound("Form '" + formCode + "' not found"));

        Principal actor = authorise(def, form, caller);
        Map<String, Object> data = payload == null ? Map.of() : payload;
        rejectForeignFields(form, data);

        if (form.startsWorkflow() != null) {
            WorkflowService.Started s = workflows.start(appCode, form.startsWorkflow(), data, actor);
            return new Result(s.record().id(), s.record().referenceNo(), s.instance().id());
        }
        RecordView r = records.create(appCode, form.entity(), data, actor);
        return new Result(r.id(), r.referenceNo(), null);
    }

    private static Principal authorise(AppDefinition def, Form form, Principal caller) {
        if (form.isPublic()) {
            if (caller != null) {
                return caller;
            }
            return def.publicRole().map(r -> Principal.anonymous(r.code())).orElseGet(() -> new Principal(null, Set.of()));
        }
        if (caller == null) {
            throw new InsufficientAuthenticationException("Authentication required");
        }
        if (!form.roles().isEmpty() && !caller.hasAnyRole(form.roles())) {
            throw new AccessDeniedException("Access denied");
        }
        return caller;
    }

    private static void rejectForeignFields(Form form, Map<String, Object> data) {
        Set<String> allowed = new LinkedHashSet<>();
        form.sections().forEach(s -> allowed.addAll(s.fields()));
        List<ApiViolation> bad = new ArrayList<>();
        for (String key : data.keySet()) {
            if (!allowed.contains(key)) {
                bad.add(new ApiViolation(key, "UNKNOWN_FIELD", "Field '" + key + "' is not part of this form"));
            }
        }
        if (!bad.isEmpty()) {
            throw new ValidationFailed("Fields not on this form: " + String.join(", ", bad.stream()
                    .map(ApiViolation::field).toList()), bad);
        }
    }
}
