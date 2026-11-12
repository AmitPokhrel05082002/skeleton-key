package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.Option;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Req 2.7, 2.8, 2.9, 2.12, 2.13: option, reference, title field, range and pattern rules. */
final class EntityFieldRules implements RuleGroup {

    static final String DUPLICATE_OPTION = "DUPLICATE_OPTION";
    static final String DANGLING_REF_ENTITY = "DANGLING_REF_ENTITY";
    static final String BAD_TITLE_FIELD = "BAD_TITLE_FIELD";
    static final String MIN_GT_MAX = "MIN_GT_MAX";
    static final String BAD_PATTERN = "BAD_PATTERN";

    @Override
    public void check(AppDefinition def, Consumer<Violation> out) {
        Set<String> entityCodes = new HashSet<>();
        def.entities().forEach(e -> entityCodes.add(e.code()));

        for (Entity entity : def.entities()) {
            String entityPath = "entities[" + entity.code() + "]";
            if (entity.titleField() != null && entity.field(entity.titleField()).isEmpty()) {
                out.accept(new Violation(BAD_TITLE_FIELD, entityPath,
                        "Entity " + entity.code() + " has titleField '" + entity.titleField()
                                + "' which is not one of its fields"));
            }
            for (Field field : entity.fields()) {
                checkField(entity, field, entityPath + ".fields[" + field.code() + "]", entityCodes, out);
            }
        }
    }

    private static void checkField(Entity entity, Field field, String path, Set<String> entityCodes,
                                   Consumer<Violation> out) {
        if (field.type() == Field.Type.SELECT || field.type() == Field.Type.MULTISELECT) {
            Set<String> seen = new HashSet<>();
            Set<String> reported = new LinkedHashSet<>();
            for (Option option : field.options()) {
                if (!seen.add(option.value())) {
                    reported.add(option.value());
                }
            }
            for (String value : reported) {
                out.accept(new Violation(DUPLICATE_OPTION, path,
                        "Field " + entity.code() + "." + field.code() + " declares option value '"
                                + value + "' more than once"));
            }
        }
        if (field.type() == Field.Type.REFERENCE && field.ref() != null && !entityCodes.contains(field.ref())) {
            out.accept(new Violation(DANGLING_REF_ENTITY, path,
                    "Field " + entity.code() + "." + field.code() + " references missing entity " + field.ref()));
        }
        if (field.min() != null && field.max() != null && field.min() > field.max()) {
            out.accept(new Violation(MIN_GT_MAX, path,
                    "Field " + entity.code() + "." + field.code() + " has min " + field.min()
                            + " greater than max " + field.max()));
        }
        if (field.pattern() != null) {
            try {
                Pattern.compile(field.pattern());
            } catch (PatternSyntaxException e) {
                out.accept(new Violation(BAD_PATTERN, path,
                        "Field " + entity.code() + "." + field.code() + " has an invalid pattern: "
                                + e.getDescription()));
            }
        }
    }
}
