package dev.skeletonkey.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description of the Skeleton Backend API. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI skeletonOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Skeleton Backend API").version("0.1.0")
                        .description("Metadata-driven records, workflow routing and definitions API."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}