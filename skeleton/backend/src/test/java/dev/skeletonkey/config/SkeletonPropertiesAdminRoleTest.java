package dev.skeletonkey.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SkeletonPropertiesAdminRoleTest {
    @Configuration
    @EnableConfigurationProperties(SkeletonProperties.class)
    static class Cfg {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Cfg.class)
            .withPropertyValues(
                    "skeletonkey.jwt.secret=0123456789abcdef0123456789abcdef",
                    "skeletonkey.jwt.expiry-seconds=3600",
                    "skeletonkey.definitions.dir=/tmp/defs");

    @Test
    void defaultsToPlatformAdmin() {
        runner.run(ctx -> assertThat(ctx.getBean(SkeletonProperties.class).adminRole())
                .isEqualTo("PLATFORM_ADMIN"));
    }

    @Test
    void acceptsConfiguredRoleCode() {
        runner.withPropertyValues("skeletonkey.admin-role=SUPER_USER_2").run(ctx ->
                assertThat(ctx.getBean(SkeletonProperties.class).adminRole()).isEqualTo("SUPER_USER_2"));
    }

    @Test
    void rejectsBlankAndMalformedRoleCodesNamingTheProperty() {
        for (String bad : new String[] {"", "   ", "admin", "1ADMIN", "A-B", "A".repeat(65)}) {
            runner.withPropertyValues("skeletonkey.admin-role=" + bad).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("adminRole");
            });
        }
    }
}
