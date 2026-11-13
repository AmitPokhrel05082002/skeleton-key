package dev.skeletonkey.record;

import dev.skeletonkey.domain.model.AppDefinition;
import java.util.Optional;

/**
 * Narrow port through which the record layer obtains the highest PUBLISHED definition of an app.
 * The DefinitionService adapts to this interface; the record layer does not depend on it directly.
 */
public interface PublishedDefinitions {

    /** The highest PUBLISHED version of the app, or empty when the app has none. */
    Optional<PublishedApp> latestPublished(String appCode);

    /**
     * One specific PUBLISHED version, used to route a running instance on the version it is pinned to (Req 5.5).
     * Never falls back to another version. The default only knows the latest version; DefinitionService overrides it.
     */
    default Optional<PublishedApp> publishedVersion(String appCode, int version) {
        return latestPublished(appCode).filter(a -> a.version() == version);
    }

    /** A published definition together with its version number. */
    record PublishedApp(int version, AppDefinition definition) {}
}
