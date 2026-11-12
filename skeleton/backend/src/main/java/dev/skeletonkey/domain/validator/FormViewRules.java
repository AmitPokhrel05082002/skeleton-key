package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Form;
import dev.skeletonkey.domain.model.View;
import dev.skeletonkey.domain.model.Workflow;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Req 2.10, 2.11, 2.14: references from forms, views and workflows to entities, roles and fields. */
final class FormViewRules implements RuleGroup {

    static final String DANGLING_FORM_REF = "DANGLING_FORM_REF";
    static final String DANGLING_VIEW_REF = "DANGLING_VIEW_REF";
    static final String WORKFLOW_ENTITY_MISMATCH = "WORKFLOW_ENTITY_MISMATCH";

    /** System columns permitted in views, from the schema's columnRef pattern. */
    static final Set<String> SYSTEM_COLUMNS = Set.of("$reference", "$status", "$step", "$assignee",
            "$createdAt", "$updatedAt", "$dueAt", "$createdBy");

    @Override
    public void check(AppDefinition def, Consumer<Violation> out) {
        Set<String> roleCodes = new HashSet<>();
        def.roles().forEach(r -> roleCodes.add(r.code()));

        for (Form form : def.forms()) {
            checkForm(def, form, roleCodes, out);
        }
        for (View view : def.views()) {
            checkView(def, view, roleCodes, out);
        }
        for (Workflow workflow : def.workflows()) {
            if (def.entity(workflow.entity()).isEmpty()) {
                out.accept(new Violation(WORKFLOW_ENTITY_MISMATCH, "workflows[" + workflow.code() + "]",
                        "Workflow " + workflow.code() + " names missing entity " + workflow.entity()));
            }
        }
    }

    private static void checkForm(AppDefinition def, Form form, Set<String> roleCodes, Consumer<Violation> out) {
        String path = "forms[" + form.code() + "]";
        Optional<Entity> entity = def.entity(form.entity());
        if (entity.isEmpty()) {
            out.accept(new Violation(DANGLING_FORM_REF, path,
                    "Form " + form.code() + " names missing entity " + form.entity()));
        }
        for (String role : form.roles()) {
            if (!roleCodes.contains(role)) {
                out.accept(new Violation(DANGLING_FORM_REF, path,
                        "Form " + form.code() + " names missing role " + role));
            }
        }
        for (Form.Section section : form.sections()) {
            for (String fieldCode : section.fields()) {
                if (entity.isPresent() && entity.get().field(fieldCode).isEmpty()) {
                    out.accept(new Violation(DANGLING_FORM_REF, path,
                            "Form " + form.code() + " names field " + fieldCode
                                    + " which does not belong to entity " + form.entity()));
                }
            }
        }
        if (form.startsWorkflow() != null) {
            Optional<Workflow> workflow = def.workflow(form.startsWorkflow());
            if (workflow.isEmpty()) {
                out.accept(new Violation(DANGLING_FORM_REF, path,
                        "Form " + form.code() + " starts missing workflow " + form.startsWorkflow()));
            } else if (!workflow.get().entity().equals(form.entity())) {
                out.accept(new Violation(WORKFLOW_ENTITY_MISMATCH, path,
                        "Form " + form.code() + " (entity " + form.entity() + ") starts workflow "
                                + form.startsWorkflow() + " of entity " + workflow.get().entity()));
            }
        }
    }

    private static void checkView(AppDefinition def, View view, Set<String> roleCodes, Consumer<Violation> out) {
        String path = "views[" + view.code() + "]";
        Optional<Entity> entity = def.entity(view.entity());
        if (entity.isEmpty()) {
            out.accept(new Violation(DANGLING_VIEW_REF, path,
                    "View " + view.code() + " names missing entity " + view.entity()));
        }
        for (String role : view.roles()) {
            if (!roleCodes.contains(role)) {
                out.accept(new Violation(DANGLING_VIEW_REF, path,
                        "View " + view.code() + " names missing role " + role));
            }
        }
        view.columns().forEach(c -> checkColumn(view, entity, c, "column", path, out));
        view.filters().forEach(c -> checkColumn(view, entity, c, "filter", path, out));
        if (view.defaultSort() != null) {
            checkColumn(view, entity, view.defaultSort().field(), "defaultSort field", path, out);
        }
    }

    private static void checkColumn(View view, Optional<Entity> entity, String column, String kind,
                                    String path, Consumer<Violation> out) {
        if (SYSTEM_COLUMNS.contains(column) || entity.isEmpty() || entity.get().field(column).isPresent()) {
            return;
        }
        out.accept(new Violation(DANGLING_VIEW_REF, path,
                "View " + view.code() + " " + kind + " " + column + " is neither a field of entity "
                        + view.entity() + " nor a system column"));
    }
}
