package dev.skeletonkey.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed, validated {@code skeletonkey.*} configuration (Req 20.3, 20.4). */
@ConfigurationProperties(prefix = "skeletonkey")
@Validated
public record SkeletonProperties(
    @NotNull @Valid Jwt jwt,
    @NotNull @Valid Definitions definitions,
    @Valid Seed seed,
    @Valid @DefaultValue Page page,
    @NotBlank @Pattern(regexp = SkeletonProperties.ROLE_CODE_PATTERN)
        @DefaultValue(SkeletonProperties.DEFAULT_ADMIN_ROLE) String adminRole) {

  /** Same pattern as an App_Definition role code. */
  public static final String ROLE_CODE_PATTERN = "^[A-Z][A-Z0-9_]{0,63}$";

  /** Platform admin role used when {@code skeletonkey.admin-role} is absent (Req 6.9). */
  public static final String DEFAULT_ADMIN_ROLE = "PLATFORM_ADMIN";

  /** Marks the canonical constructor for binding, since a second constructor exists. */
  @ConstructorBinding
  public SkeletonProperties {}

  /** Convenience constructor using the default platform admin role. */
  public SkeletonProperties(Jwt jwt, Definitions definitions, Seed seed, Page page) {
    this(jwt, definitions, seed, page, DEFAULT_ADMIN_ROLE);
  }

  public record Jwt(@NotBlank @Size(min = 32) String secret, @Positive long expirySeconds) {}

  public record Definitions(@NotBlank String dir, @DefaultValue("true") boolean autoPublish) {}

  /** Optional seed file; {@code file} may be null or empty. */
  public record Seed(String file) {}

  public record Page(@Positive @Max(100) @DefaultValue("100") int maxSize) {}
}
