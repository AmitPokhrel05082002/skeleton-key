package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Parsed definitions cached by (appCode, version). Published versions are immutable, so entries never go stale. */
@Component
public class DefinitionCache {
    public record Key(String appCode, int version) {}

    private final DefinitionParser parser;
    private final ConcurrentHashMap<Key, AppDefinition> cache = new ConcurrentHashMap<>();

    public DefinitionCache(DefinitionParser parser) {
        this.parser = parser;
    }

    /** Returns the cached model, parsing the document from {@code source} on a miss. */
    public AppDefinition get(String appCode, int version, Supplier<JsonNode> source) {
        return cache.computeIfAbsent(new Key(appCode, version), k -> parser.parse(source.get()));
    }

    public Optional<AppDefinition> peek(String appCode, int version) {
        return Optional.ofNullable(cache.get(new Key(appCode, version)));
    }

    public void evict(String appCode, int version) {
        cache.remove(new Key(appCode, version));
    }

    public int size() {
        return cache.size();
    }
}
