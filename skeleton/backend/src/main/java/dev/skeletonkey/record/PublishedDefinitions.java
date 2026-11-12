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

    /** A published definition together with its version number. */
    record PublishedApp(int version, AppDefinition definition) {}
}
