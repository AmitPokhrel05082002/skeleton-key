package dev.skeletonkey.testing;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Role;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/** Generators for callers: authenticated principals with 0 or more roles, and anonymous callers. */
public final class PrincipalArbitraries {

    private PrincipalArbitraries() {}

    /** A user id; deterministic from two longs so shrinking is stable. */
    public static Arbitrary<UUID> userIds() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
    }

    /** Authenticated principals holding a subset (possibly empty) of the given role codes. */
    public static Arbitrary<Principal> principal(Collection<String> roleCodes) {
        List<String> codes = new ArrayList<>(roleCodes);
        Arbitrary<Set<String>> roles = codes.isEmpty()
                ? Arbitraries.just(Set.<String>of())
                : Arbitraries.of(codes).set().ofMaxSize(codes.size());
        return Combinators.combine(userIds(), roles).as(Principal::new);
    }

    /** Authenticated principals holding a subset of the roles of the definition. */
    public static Arbitrary<Principal> principal(AppDefinition def) {
        return principal(def.roles().stream().map(Role::code).toList());
    }

    /** The anonymous caller of the definition's public role, or empty roles when there is none. */
    public static Principal anonymous(AppDefinition def) {
        return def.publicRole().map(r -> Principal.anonymous(r.code()))
                .orElseGet(() -> new Principal(null, new LinkedHashSet<>()));
    }

    /** Principals over a default role set, for tests that do not need a definition. */
    public static Arbitrary<Principal> principal() {
        return principal(List.of("ROLE_1", "ROLE_2", "ROLE_3", "ROLE_4"));
    }
}
