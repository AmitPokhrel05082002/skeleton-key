package dev.skeletonkey.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users from the optional Seed_File at startup (Req 17.1-17.4, 17.7, 17.8, 6.8). Runs before
 * {@code StartupLoader}. Seed users may hold any role code, including the Platform_Admin role, which is not a role of
 * any App_Definition (Req 6.9). A malformed file stops startup: the exception propagates out of the runner, so the
 * process exits non-zero, and its message names the file. The whole file is checked before any user is stored.
 */
@Component
@Order(1)
public class SeedLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    /** A BCrypt hash: $2a$/$2b$/$2y$, two-digit cost, 53 characters of salt and digest. */
    static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d\\d\\$.{53}$");
    private static final Pattern ROLE_CODE = Pattern.compile(SkeletonProperties.ROLE_CODE_PATTERN);
    private static final Set<String> USER_KEYS = Set.of("username", "password", "passwordHash", "roles", "active");
    private static final int MAX_USERNAME = 256;

    private final SkeletonProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    public SeedLoader(SkeletonProperties props, UserRepository users, PasswordEncoder encoder, ObjectMapper mapper) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    /** Raised for an unreadable or malformed Seed_File; the message names the file. */
    public static class SeedException extends IllegalStateException {
        public SeedException(String file, String reason, Throwable cause) {
            super("Invalid seed file '" + file + "': " + reason, cause);
        }
    }

    private record SeedUser(String username, String password, String passwordHash, Set<String> roles, boolean active) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String file = props.seed() == null ? null : props.seed().file();
        if (file == null || file.isBlank()) {
            log.info("No seed file configured; skipping user seeding");
            return;
        }
        List<SeedUser> parsed = parse(file);
        int created = 0;
        for (SeedUser u : parsed) {
            if (users.findByUsername(u.username()).isPresent()) {
                continue; // existing users are left unchanged (Req 17.3, 17.4)
            }
            String hash = u.passwordHash() != null ? u.passwordHash() : encoder.encode(u.password());
            users.insert(new UserAccount(UUID.randomUUID(), u.username(), hash, u.username(), u.active(), u.roles()));
            created++;
        }
        log.info("Seed file {}: {} user(s) created, {} already present", file, created, parsed.size() - created);
    }

    private List<SeedUser> parse(String file) {
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(Path.of(file)));
        } catch (IOException | RuntimeException e) {
            throw new SeedException(file, "cannot be read as JSON (" + e.getMessage() + ")", e);
        }
        if (root == null || !root.isObject()) {
            throw new SeedException(file, "root must be an object with a 'users' array", null);
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!key.equals("users")) {
                throw new SeedException(file, "unknown property '" + key + "'", null);
            }
        }
        JsonNode arr = root.get("users");
        if (arr == null || !arr.isArray()) {
            throw new SeedException(file, "'users' must be an array", null);
        }
        List<SeedUser> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            out.add(parseUser(file, i, arr.get(i)));
        }
        return out;
    }

    private SeedUser parseUser(String file, int index, JsonNode n) {
        String at = "users[" + index + "]";
        if (!n.isObject()) {
            throw new SeedException(file, at + " must be an object", null);
        }
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!USER_KEYS.contains(key)) {
                throw new SeedException(file, at + " has unknown property '" + key + "'", null);
            }
        }
        String username = text(file, at, n, "username");
        if (username == null || username.isBlank() || username.length() > MAX_USERNAME) {
            throw new SeedException(file, at + ".username must be 1 to " + MAX_USERNAME + " characters", null);
        }
        String password = text(file, at, n, "password");
        String hash = text(file, at, n, "passwordHash");
        if ((password == null) == (hash == null)) {
            throw new SeedException(file, at + " must have exactly one of 'password' and 'passwordHash'", null);
        }
        if (password != null && password.isEmpty()) {
            throw new SeedException(file, at + ".password must not be empty", null);
        }
        if (hash != null && !BCRYPT.matcher(hash).matches()) {
            throw new SeedException(file, at + ".passwordHash is not a BCrypt hash", null);
        }
        Set<String> roles = new LinkedHashSet<>();
        JsonNode r = n.get("roles");
        if (r != null) {
            if (!r.isArray()) {
                throw new SeedException(file, at + ".roles must be an array of role codes", null);
            }
            for (JsonNode code : r) {
                if (!code.isTextual() || !ROLE_CODE.matcher(code.asText()).matches()) {
                    throw new SeedException(file, at + ".roles contains an invalid role code: " + code, null);
                }
                roles.add(code.asText());
            }
        }
        boolean active = true;
        JsonNode a = n.get("active");
        if (a != null) {
            if (!a.isBoolean()) {
                throw new SeedException(file, at + ".active must be a boolean", null);
            }
            active = a.asBoolean();
        }
        return new SeedUser(username, password, hash, roles, active);
    }

    private static String text(String file, String at, JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null) {
            return null;
        }
        if (!v.isTextual()) {
            throw new SeedException(file, at + "." + key + " must be a string", null);
        }
        return v.asText();
    }
}
