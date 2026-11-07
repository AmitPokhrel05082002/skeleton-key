package dev.skeletonkey.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed, validated {@code skeletonkey.*} configuration (Req 20.3, 20.4). */
@ConfigurationProperties(prefix = "skeletonkey")
@Validated
public record SkeletonProperties(
    @NotNull @Valid Jwt jwt,
    @NotNull @Valid Definitions definitions,
    @Valid Seed seed,
    @Valid @DefaultValue Page page) {

  public record Jwt(@NotBlank @Size(min = 32) String secret, @Positive long expirySeconds) {}

  public record Definitions(@NotBlank String dir, @DefaultValue("true") boolean autoPublish) {}

  /** Optional seed file; {@code file} may be null or empty. */
  public record Seed(String file) {}

  public record Page(@Positive @Max(100) @DefaultValue("100") int maxSize) {}
}
