package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.AppDefinition;
import java.util.function.Consumer;

/**
 * A group of related validation rules. Groups are independent: each one inspects the whole
 * definition and reports every violation it finds, never stopping at the first.
 */
@FunctionalInterface
public interface RuleGroup {

    void check(AppDefinition def, Consumer<Violation> out);
}
