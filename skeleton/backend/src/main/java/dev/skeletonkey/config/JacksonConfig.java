package dev.skeletonkey.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serializes every Instant as UTC with millisecond precision: yyyy-MM-dd'T'HH:mm:ss.SSS'Z' (Req 12.12). */
@Configuration
public class JacksonConfig {
    public static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static class InstantSerializer extends StdSerializer<Instant> {
        public InstantSerializer() { super(Instant.class); }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(INSTANT_FORMAT.format(value));
        }
    }

    public static SimpleModule instantModule() {
        SimpleModule m = new SimpleModule("skeletonkey-instant");
        m.addSerializer(Instant.class, new InstantSerializer());
        return m;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer instantCustomizer() {
        return b -> b.modulesToInstall(instantModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}