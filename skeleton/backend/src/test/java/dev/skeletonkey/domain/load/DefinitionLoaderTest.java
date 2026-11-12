package dev.skeletonkey.domain.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DefinitionLoaderTest {
    private final DefinitionLoader loader = new DefinitionLoader();

    @Test
    void acceptsTheGrievanceExample() throws Exception {
        String json = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
        LoadedDefinition loaded = loader.load(json);
        assertThat(loaded.contentHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void reportsLineAndColumnOfFirstSyntaxError() {
        assertThatThrownBy(() -> loader.load("{\n  \"a\": 1,\n  \"b\": }"))
                .isInstanceOfSatisfying(DefinitionSyntaxException.class, e -> {
                    assertThat(e.line()).isEqualTo(3);
                    assertThat(e.column()).isGreaterThan(0);
                });
    }

    @Test
    void reportsSchemaViolationsWithJsonPointers() {
        assertThatThrownBy(() -> loader.load("{\"schemaVersion\":1,\"app\":{\"code\":\"bad\",\"name\":\"X\"}}"))
                .isInstanceOfSatisfying(DefinitionSchemaException.class, e -> {
                    assertThat(e.violations()).extracting(SchemaViolation::path).contains("/app/code", "");
                });
    }

    @Test
    void hashIgnoresKeyOrderWhitespaceAndNumberFormatting() {
        String a = loader.contentHash(loader.parse("{\"x\":1.50,\"y\":[1,2],\"z\":{\"a\":1,\"b\":2}}"));
        String b = loader.contentHash(loader.parse("{ \"z\":{\"b\":2,\"a\":1}, \"y\":[1,2], \"x\":1.5 }"));
        String c = loader.contentHash(loader.parse("{\"x\":1.5,\"y\":[2,1],\"z\":{\"a\":1,\"b\":2}}"));
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
