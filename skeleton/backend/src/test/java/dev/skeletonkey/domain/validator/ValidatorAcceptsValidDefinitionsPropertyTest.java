package dev.skeletonkey.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.DefinitionArbitraries.GeneratedDefinition;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;

/**
 * Feature: skeleton-backend, Property 4: Validator accepts every valid definition.
 */
class ValidatorAcceptsValidDefinitionsPropertyTest {

    private static final DefinitionLoader LOADER = new DefinitionLoader();
    private static final Validator VALIDATOR = new Validator(DefinitionArbitraries.handlerCatalog());

    /**
     * Feature: skeleton-backend, Property 4: Validator accepts every valid definition
     *
     * <p><b>Validates: Requirements 2.1, 2.3, 18.2</b>
     */
    @Property(tries = 300)
    void validatorAcceptsEveryValidDefinition(@ForAll("definitions") GeneratedDefinition generated) {
        assertThat(LOADER.checkSchema(generated.json())).isEmpty();
        assertThat(VALIDATOR.validate(generated.model()).violations()).isEmpty();
    }

    @Provide
    Arbitrary<GeneratedDefinition> definitions() {
        return DefinitionArbitraries.validDefinition();
    }
}
