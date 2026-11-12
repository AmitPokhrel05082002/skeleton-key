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

    /**
     * Full publish-time validator: structural groups plus expression and handler rules (Req 4).
     * There is deliberately no no-arg constructor, so a publish path cannot skip the expression rules.
     * Use {@code new Validator(structuralGroups())} only in tests that target structural rules alone.
     */
    public Validator(HandlerCatalog handlers) {
        this(withExpressionRules(handlers));
    }

    private static List<RuleGroup> withExpressionRules(HandlerCatalog handlers) {
        List<RuleGroup> all = new ArrayList<>(structuralGroups());
        all.add(new ExpressionRules(handlers));
        return all;
    }

    public static List<RuleGroup> structuralGroups() {
        return List.of(new CollectionRules(), new EntityFieldRules(), new FormViewRules(),
                new WorkflowGraphRules());
    }

    public ValidationReport validate(AppDefinition def) {
        List<Violation> found = new ArrayList<>();
        for (RuleGroup group : groups) {
            group.check(def, found::add);
        }
        return new ValidationReport(found);
    }
}
