package dev.skeletonkey.domain.load;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Parses an App_Definition document, checks it against {@code schema/app-definition.schema.json} (draft 2020-12)
 * and computes its Content_Hash. Pure and stateless after construction; it stores nothing.
 */
@Component
public class DefinitionLoader {
    static final String SCHEMA_RESOURCE = "/schema/app-definition.schema.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JsonSchema schema;

    public DefinitionLoader() {
        try (InputStream in = DefinitionLoader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Schema not found on classpath: " + SCHEMA_RESOURCE);
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build();
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(mapper.readTree(in), config);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCHEMA_RESOURCE, e);
        }
    }

    /** Parses and schema-checks the text. Throws {@link DefinitionSyntaxException} (400) or {@link DefinitionSchemaException} (422). */
    public LoadedDefinition load(String json) {
        JsonNode tree = parse(json);
        List<SchemaViolation> violations = checkSchema(tree);
        if (!violations.isEmpty()) throw new DefinitionSchemaException(violations);
        return new LoadedDefinition(tree, contentHash(tree));
    }

    /** Parses JSON text; the first syntax error is reported with 1-based line and column. */
    public JsonNode parse(String json) {
        if (json == null) throw new DefinitionSyntaxException("Document is empty", 1, 1);
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree == null || tree.isMissingNode()) throw new DefinitionSyntaxException("Document is empty", 1, 1);
            return tree;
        } catch (JsonParseException e) {
            JsonLocation loc = e.getLocation();
            int line = loc == null ? 1 : Math.max(1, loc.getLineNr());
            int col = loc == null ? 1 : Math.max(1, loc.getColumnNr());
            throw new DefinitionSyntaxException(e.getOriginalMessage(), line, col);
        } catch (JsonProcessingException e) {
            JsonLocation loc = e.getLocation();
            throw new DefinitionSyntaxException(e.getOriginalMessage(),
                    loc == null ? 1 : Math.max(1, loc.getLineNr()), loc == null ? 1 : Math.max(1, loc.getColumnNr()));
        }
    }

    /** Every schema violation, sorted by path then message, with an RFC 6901 JSON Pointer path ("" is the root). */
    public List<SchemaViolation> checkSchema(JsonNode tree) {
        List<SchemaViolation> out = new ArrayList<>();
        for (ValidationMessage m : schema.validate(tree)) {
            out.add(new SchemaViolation("SCHEMA", m.getInstanceLocation().toString(), m.getMessage()));
        }
        out.sort(Comparator.comparing(SchemaViolation::path).thenComparing(SchemaViolation::message));
        return out;
    }

    /** Lowercase hex SHA-256 of {@link #canonicalJson(JsonNode)} encoded as UTF-8. */
    public String contentHash(JsonNode tree) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(tree).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keys sorted recursively, no whitespace, arrays in order, numbers as stripped BigDecimal text. */
    public String canonicalJson(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private void write(JsonNode n, StringBuilder sb) {
        if (n.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(e.getKey())).append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                write(n.get(i), sb);
            }
            sb.append(']');
        } else if (n.isNumber()) {
            BigDecimal d = n.decimalValue();
            sb.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toString());
        } else if (n.isTextual()) {
            sb.append(quote(n.textValue()));
        } else if (n.isBoolean()) {
            sb.append(n.booleanValue());
        } else {
            sb.append("null");
        }
    }

    private String quote(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
