package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Role;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Req 2.5, 2.6: duplicate codes per collection and at most one public role. */
final class CollectionRules implements RuleGroup {

    static final String DUPLICATE_CODE = "DUPLICATE_CODE";
    static final String MULTIPLE_PUBLIC_ROLES = "MULTIPLE_PUBLIC_ROLES";

    @Override
    public void check(AppDefinition def, Consumer<Violation> out) {
        duplicates("roles", "roles", def.roles(), Role::code, out);
        duplicates("entities", "entities", def.entities(), e -> e.code(), out);
        duplicates("forms", "forms", def.forms(), f -> f.code(), out);
        duplicates("views", "views", def.views(), v -> v.code(), out);
        duplicates("workflows", "workflows", def.workflows(), w -> w.code(), out);
        def.entities().forEach(e -> duplicates("fields of entity " + e.code(),
                "entities[" + e.code() + "].fields", e.fields(), f -> f.code(), out));

        List<String> publicRoles = def.roles().stream().filter(Role::isPublic).map(Role::code).toList();
        if (publicRoles.size() > 1) {
            for (String code : publicRoles) {
                out.accept(new Violation(MULTIPLE_PUBLIC_ROLES, "roles[" + code + "]",
                        "Role " + code + " is public, but at most one role may be public"));
            }
        }
    }

    private static <T> void duplicates(String collection, String pathPrefix, List<T> items,
                                       Function<T, String> codeOf, Consumer<Violation> out) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new LinkedHashSet<>();
        for (T item : items) {
            String code = codeOf.apply(item);
            if (!seen.add(code)) {
                reported.add(code);
            }
        }
        for (String code : reported) {
            out.accept(new Violation(DUPLICATE_CODE, pathPrefix + "[" + code + "]",
                    "Duplicate code " + code + " in " + collection));
        }
    }
}
