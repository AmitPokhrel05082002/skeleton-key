package dev.skeletonkey.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skeletonkey.config.JacksonConfig;
import java.util.List;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/** Gives a test MVC context the production Instant format (yyyy-MM-dd'T'HH:mm:ss.SSS'Z'). */
final class JsonTestSupport {
    private JsonTestSupport() {}

    static void useProductionInstantFormat(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> c : converters) {
            if (c instanceof MappingJackson2HttpMessageConverter j) {
                j.setObjectMapper(new ObjectMapper().registerModule(JacksonConfig.instantModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
            }
        }
    }
}