package dev.skeletonkey.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.domain.expr.ExprAnalysis;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.validator.ValidationReport;
import dev.skeletonkey.domain.validator.Validator;
import dev.skeletonkey.testing.ActionSequenceArbitraries.ActionAttempt;
import dev.skeletonkey.testing.DefinitionArbitraries.GeneratedDefinition;
import dev.skeletonkey.testing.Mutations.MutatedDefinition;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Smoke checks that the shared generators produce what their contracts promise. */
class GeneratorsSmokeTest {

    private static final DefinitionLoader LOADER = new DefinitionLoader();
    private static final Validator VALIDATOR = new Validator(DefinitionArbitraries.handlerCatalog());
    private static final ExpressionEngine ENGINE = new ExpressionEngine();

    @Property(tries = 40)
    void validDefinitionsPassSchemaAndValidator(@ForAll("valid") GeneratedDefinition d) {
        assertThat(LOADER.checkSchema(d.json())).isEmpty();
        ValidationReport report = VALIDATOR.validate(d.model());
        assertThat(report.violations()).isEmpty();
        // Text form re-parses to the same model.
        JsonNode reparsed = LOADER.parse(d.jsonText());
        assertThat(new DefinitionParser().parse(reparsed)).isEqualTo(d.model());
        assertThat(d.model().roles()).hasSizeBetween(1, 20);
        assertThat(d.model().entities()).hasSizeBetween(1, 10);
    }

    @Property(tries = 60)
    void mutatedDefinitionsStaySchemaValidAndReportTheDefect(@ForAll("mutated") MutatedDefinition m) {
        assertThat(LOADER.checkSchema(m.json())).isEmpty();
        assertThat(VALIDATOR.validate(m.model()).violations())
                .extracting(v -> v.rule())
                .contains(m.expectedRule());
    }

    @Property(tries = 100)
    void expressionsRespectDepthAndPassAnalysis(@ForAll("expressions") JsonNode expr) {
        assertThat(ExpressionArbitraries.depthOf(expr)).isBetween(0, 10);
        ExprAnalysis a = ENGINE.analyze(expr);
        assertThat(a.parseErrors()).isEmpty();
        assertThat(a.unsupportedOperators()).isEmpty();
        assertThat(a.invalidRootVars()).isEmpty();
    }

    @Property(tries = 20)
    void principalsAndSequencesAreGenerated(@ForAll("valid") GeneratedDefinition d) {
        Principal p = PrincipalArbitraries.principal(d.model()).sample();
        assertThat(d.model().roles().stream().map(r -> r.code())).containsAll(p.roles());
        List<ActionAttempt> seq = ActionSequenceArbitraries.sequences(d.model(), 8).sample();
        assertThat(seq).hasSizeLessThanOrEqualTo(8);
    }

    @Provide
    Arbitrary<GeneratedDefinition> valid() {
        return DefinitionArbitraries.validDefinition();
    }

    @Provide
    Arbitrary<MutatedDefinition> mutated() {
        return Mutations.oneOfTenDefects();
    }

    @Provide
    Arbitrary<JsonNode> expressions() {
        return ExpressionArbitraries.anyJsonLogic();
    }

    @Property(tries = 1)
    void allTenDefectKindsExist() {
        assertThat(Mutations.Defect.values()).hasSize(10);
        assertThat(Arbitraries.of(Mutations.Defect.values()).sample()).isNotNull();
    }
}
