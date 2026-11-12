package dev.skeletonkey.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SeedLoaderTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final InMemoryUserRepository users = new InMemoryUserRepository();
    @TempDir Path tmp;

    private SeedLoader loader(String file) {
        SkeletonProperties props = new SkeletonProperties(new SkeletonProperties.Jwt("x".repeat(32), 60), null,
                file == null ? null : new SkeletonProperties.Seed(file), null);
        return new SeedLoader(props, users, encoder, new ObjectMapper());
    }

    private String write(String content) throws IOException {
        Path p = tmp.resolve("seed.json");
        Files.writeString(p, content);
        return p.toString();
    }

    private void run(SeedLoader l) {
        l.run(new DefaultApplicationArguments());
    }

    @Test
    void runsFirst() {
        assertThat(SeedLoader.class.getAnnotation(Order.class).value()).isEqualTo(1);
    }

    @Test
    void plainPasswordIsHashedAndPreHashedIsKept() throws Exception {
        String hash = encoder.encode("pre");
        run(loader(write("{\"users\":[{\"username\":\"a\",\"password\":\"secret\",\"roles\":[\"AGENT\"]},"
                + "{\"username\":\"b\",\"passwordHash\":\"" + hash + "\",\"roles\":[],\"active\":false}]}")));

        UserAccount a = users.findByUsername("a").orElseThrow();
        assertThat(a.passwordHash()).isNotEqualTo("secret");
        assertThat(encoder.matches("secret", a.passwordHash())).isTrue();
        assertThat(a.active()).isTrue();
        assertThat(a.roles()).containsExactly("AGENT");
        UserAccount b = users.findByUsername("b").orElseThrow();
        assertThat(b.passwordHash()).isEqualTo(hash);
        assertThat(b.active()).isFalse();
    }

    @Test
    void existingUsersAreSkippedAndRestartCreatesNothing() throws Exception {
        UserAccount existing = new UserAccount(UUID.randomUUID(), "a", "old", "a", true, Set.of("X"));
        users.insert(existing);
        String file = write("{\"users\":[{\"username\":\"a\",\"password\":\"new\",\"roles\":[\"AGENT\"]},"
                + "{\"username\":\"c\",\"password\":\"p\"}]}");
        run(loader(file));
        run(loader(file));

        assertThat(users.findByUsername("a")).contains(existing);
        assertThat(users.findByUsername("c")).isPresent();
    }

    @Test
    void adminRoleNeedNotBeAnAppRole() throws Exception {
        run(loader(write("{\"users\":[{\"username\":\"root\",\"password\":\"p\",\"roles\":[\"PLATFORM_ADMIN\"]}]}")));
        assertThat(users.findByUsername("root").orElseThrow().roles()).containsExactly("PLATFORM_ADMIN");
    }

    @Test
    void noSeedPathMeansNoSeeding() {
        run(loader(null));
        run(loader(""));
        assertThat(users.findByUsername("a")).isEmpty();
    }

    @Test
    void malformedSeedFailsNamingTheFileAndStoresNothing() throws Exception {
        String[] bad = {
            "{not json",
            "[]",
            "{\"users\":{}}",
            "{\"users\":[{\"username\":\"ok\",\"password\":\"p\"},{\"username\":\"x\"}]}",
            "{\"users\":[{\"username\":\"ok\",\"password\":\"p\"},{\"username\":\"x\",\"password\":\"p\",\"passwordHash\":\"h\"}]}",
            "{\"users\":[{\"username\":\"ok\",\"password\":\"p\"},{\"username\":\"x\",\"passwordHash\":\"nothash\"}]}",
            "{\"users\":[{\"username\":\"ok\",\"password\":\"p\"},{\"username\":\"x\",\"password\":\"p\",\"roles\":[\"bad role\"]}]}",
            "{\"users\":[{\"username\":\"ok\",\"password\":\"p\"},{\"username\":\"x\",\"password\":\"p\",\"active\":\"yes\"}]}",
            "{\"users\":[{\"username\":\"ok\",\"password\":\"p\"},{\"username\":\"x\",\"password\":\"p\",\"extra\":1}]}",
        };
        for (String content : bad) {
            String file = write(content);
            assertThatThrownBy(() -> run(loader(file))).isInstanceOf(SeedLoader.SeedException.class)
                    .hasMessageContaining(file);
            assertThat(users.findByUsername("ok")).as(content).isEmpty();
        }
    }

    @Test
    void missingFileFailsNamingTheFile() {
        String file = tmp.resolve("absent.json").toString();
        assertThatThrownBy(() -> run(loader(file))).isInstanceOf(SeedLoader.SeedException.class)
                .hasMessageContaining(file);
    }
}
