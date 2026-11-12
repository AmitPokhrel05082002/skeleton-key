package dev.skeletonkey.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.Mutations;
import dev.skeletonkey.testing.Mutations.Defect;
import dev.skeletonkey.testing.Mutations.MutatedDefinition;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: skeleton-backend, Property 5: Validator detects each injected defect.
 *
 * <p><b>Validates: Requirements 2.2, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13, 2.14, 3.1, 3.2, 3.3,
 * 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14, 3.15, 4.3, 4.4, 4.5, 18.4</b>
 */
class ValidatorDetectsInjectedDefectsPropertyTest {

    private static final DefinitionLoader LOADER = new DefinitionLoader();
    private static final Validator VALIDATOR = new Validator(DefinitionArbitraries.handlerCatalog());

    private static void check(MutatedDefinition m) {
        // The original is valid, so any violation comes from the injected defect.
        assertThat(VALIDATOR.validate(m.original().model()).violations()).isEmpty();
        // The mutation is invisible to the schema, only the Validator can find it.
        assertThat(LOADER.checkSchema(m.json())).isEmpty();
        assertThat(VALIDATOR.validate(m.model()).violations())
                .as("defect %s", m.defect())
                .extracting(v -> v.rule())
                .contains(m.expectedRule());
    }

    @Property(tries = 300)
    void anyInjectedDefectIsReported(@ForAll("anyDefect") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void duplicateRoleCode(@ForAll("duplicateRole") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void twoPublicRoles(@ForAll("twoPublic") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void danglingReferenceEntity(@ForAll("danglingRef") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void minGreaterThanMax(@ForAll("minMax") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void invalidPattern(@ForAll("badPattern") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void formNamesMissingEntity(@ForAll("formEntity") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void missingStartStep(@ForAll("startStep") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void unknownActionHandler(@ForAll("handler") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void unsupportedOperator(@ForAll("operator") MutatedDefinition m) {
        check(m);
    }

    @Property(tries = 40)
    void unsettableStatus(@ForAll("unsetStatus") MutatedDefinition m) {
        check(m);
    }

    @Provide
    Arbitrary<MutatedDefinition> anyDefect() {
        return Mutations.oneOfTenDefects();
    }

    @Provide
    Arbitrary<MutatedDefinition> duplicateRole() {
        return Mutations.withDefect(Defect.DUPLICATE_ROLE_CODE);
    }

    @Provide
    Arbitrary<MutatedDefinition> twoPublic() {
        return Mutations.withDefect(Defect.TWO_PUBLIC_ROLES);
    }

    @Provide
    Arbitrary<MutatedDefinition> danglingRef() {
        return Mutations.withDefect(Defect.DANGLING_REFERENCE_ENTITY);
    }

    @Provide
    Arbitrary<MutatedDefinition> minMax() {
        return Mutations.withDefect(Defect.MIN_GREATER_THAN_MAX);
    }

    @Provide
    Arbitrary<MutatedDefinition> badPattern() {
        return Mutations.withDefect(Defect.INVALID_PATTERN);
    }

    @Provide
    Arbitrary<MutatedDefinition> formEntity() {
        return Mutations.withDefect(Defect.FORM_NAMES_MISSING_ENTITY);
    }

    @Provide
    Arbitrary<MutatedDefinition> startStep() {
        return Mutations.withDefect(Defect.MISSING_START_STEP);
    }

    @Provide
    Arbitrary<MutatedDefinition> handler() {
        return Mutations.withDefect(Defect.UNKNOWN_ACTION_HANDLER);
    }

    @Provide
    Arbitrary<MutatedDefinition> operator() {
        return Mutations.withDefect(Defect.UNSUPPORTED_OPERATOR);
    }

    @Provide
    Arbitrary<MutatedDefinition> unsetStatus() {
        return Mutations.withDefect(Defect.UNSETTABLE_STATUS);
    }
}
