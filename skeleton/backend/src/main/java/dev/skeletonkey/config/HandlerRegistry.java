package dev.skeletonkey.config;

import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.plugin.PluginHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link PluginHandler} bean keyed by bean name (Req 13.2), and the {@link HandlerCatalog} adapter the
 * publish-time validator uses. Duplicate bean names are rejected by Spring Boot itself (bean definition overriding
 * is off by default), so startup fails with a BeanDefinitionOverrideException naming the bean (Req 13.3).
 */
@Component
public class HandlerRegistry implements HandlerCatalog {

    private final Map<String, PluginHandler> handlers;

    /** Spring injects all PluginHandler beans as a map keyed by bean name. */
    public HandlerRegistry(Map<String, PluginHandler> handlers) {
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
    }

    public Optional<PluginHandler> find(String name) {
        return Optional.ofNullable(name == null ? null : handlers.get(name));
    }

    @Override
    public boolean contains(String handlerName) {
        return handlerName != null && handlers.containsKey(handlerName);
    }
}
