package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import java.util.Optional;
import java.util.function.Consumer;

/** Public tracking: the contact field exists and is EMAIL, and records have a reference number to look up. */
final class TrackingRules implements RuleGroup {

    static final String BAD_TRACKING_FIELD = "BAD_TRACKING_FIELD";
    static final String TRACKING_FIELD_NOT_EMAIL = "TRACKING_FIELD_NOT_EMAIL";
    static final String TRACKING_NEEDS_REFERENCE = "TRACKING_NEEDS_REFERENCE";

    @Override
    public void check(AppDefinition def, Consumer<Violation> out) {
        for (Entity entity : def.entities()) {
            Entity.Tracking tracking = entity.tracking();
            if (tracking == null) {
                continue;
            }
            String path = "entities[" + entity.code() + "].tracking";
            Optional<Field> field = entity.field(tracking.contactField());
            if (field.isEmpty()) {
                out.accept(new Violation(BAD_TRACKING_FIELD, path + ".contactField",
                        "Entity " + entity.code() + " tracks by contactField '" + tracking.contactField()
                                + "' which is not one of its fields"));
            } else if (field.get().type() != Field.Type.EMAIL) {
                out.accept(new Violation(TRACKING_FIELD_NOT_EMAIL, path + ".contactField",
                        "Entity " + entity.code() + " tracks by field '" + tracking.contactField() + "' of type "
                                + field.get().type() + "; the contact field must be EMAIL"));
            }
            if (entity.referencePrefix() == null || entity.referencePrefix().isBlank()) {
                out.accept(new Violation(TRACKING_NEEDS_REFERENCE, path,
                        "Entity " + entity.code() + " enables tracking but has no referencePrefix"));
            }
        }
    }
}
