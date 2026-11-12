package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.AppDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * Publish-time validator. Runs every rule group unconditionally over the parsed model and
 * returns one sorted report. Pure and deterministic; a failing rule group never hides others.
 */
public class Validator {

    private final List<RuleGroup> groups;

    public Validator(List<RuleGroup> groups) {
        this.groups = List.copyOf(groups);
    }

    /** Validator with the structural rule groups (collections, entities and fields, forms and views). */
    public Validator() {
        this(structuralGroups());
    }

    public static List<RuleGroup> structuralGroups() {
        return List.of(new CollectionRules(), new EntityFieldRules(), new FormViewRules());
    }

    public ValidationReport validate(AppDefinition def) {
        List<Violation> found = new ArrayList<>();
        for (RuleGroup group : groups) {
            group.check(def, found::add);
        }
        return new ValidationReport(found);
    }
}
