package dev.skeletonkey.app;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.PageResult;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.UserRow;
import dev.skeletonkey.persistence.UserSearch;
import dev.skeletonkey.web.ActiveUserCache;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.BadRequest;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration for the platform admin (Part A of user-admin-and-tracking). Validates input, checks roles
 * against {@link RoleOptionsService}, protects the acting admin from locking themselves out, and writes audit entries
 * that never contain a password or hash.
 */
@Service
public class UserAdminService {

    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 64;
    public static final int DISPLAY_NAME_MAX = 120;
    public static final int PASSWORD_MIN_BYTES = 8;
    public static final int PASSWORD_MAX_BYTES = 72;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final String USER = "user";

    /** Create command; {@code roles} and {@code active} may be null (defaults: none, true). */
    public record CreateUser(String username, String displayName, String password, List<String> roles,
                             Boolean active) {}

    /** One page of the admin listing with the normalised sort it was produced with. */
    public record UserPage(List<UserRow> items, int page, int size, long total, String sort) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final RoleOptionsService roleOptions;
    private final AdminGuard admin;
    private final AuditService audit;
    private final ObjectProvider<ActiveUserCache> activeCache;
    private final int maxPageSize;

    public UserAdminService(UserRepository users, PasswordEncoder encoder, RoleOptionsService roleOptions,
                            AdminGuard admin, AuditService audit, ObjectProvider<ActiveUserCache> activeCache,
                            dev.skeletonkey.config.SkeletonProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.roleOptions = roleOptions;
        this.admin = admin;
        this.audit = audit;
        this.activeCache = activeCache;
        this.maxPageSize = props.page() == null ? 100 : props.page().maxSize();
    }

    @Transactional(readOnly = true)
    public UserPage search(String q, String role, Boolean active, String page, String size, String sort) {
        List<ApiViolation> bad = new ArrayList<>();
        int p = parseInt(page, 0, 0, Integer.MAX_VALUE, "page", bad);
        int s = parseInt(size, DEFAULT_PAGE_SIZE, 1, Integer.MAX_VALUE, "size", bad);
        UserSearch.Field field = UserSearch.Field.USERNAME;
        boolean asc = true;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", -1);
            var parsed = UserSearch.Field.fromApi(parts[0].trim());
            if (parsed.isEmpty() || parts.length > 2
                    || (parts.length == 2 && !parts[1].trim().equalsIgnoreCase("asc")
                            && !parts[1].trim().equalsIgnoreCase("desc"))) {
                bad.add(new ApiViolation("sort", "INVALID",
                        "sort must be one of username, displayName, active, createdAt, optionally followed by ,asc or ,desc"));
            } else {
                field = parsed.get();
                asc = parts.length < 2 || parts[1].trim().equalsIgnoreCase("asc");
            }
        }
        if (!bad.isEmpty()) {
            throw new BadRequest("Invalid listing parameters", bad);
        }
        s = Math.min(s, maxPageSize);
        PageResult<UserRow> r = users.search(new UserSearch(q, role, active, p, s, field, asc));
        return new UserPage(r.items(), r.page(), r.size(), r.total(), sortLabel(field, asc));
    }

    public static String sortLabel(UserSearch.Field f, boolean asc) {
        return f.apiName() + (asc ? ",asc" : ",desc");
    }

    @Transactional(readOnly = true)
    public UserRow get(UUID id) {
        return users.findRow(id).orElseThrow(() -> new NotFound("User not found"));
    }

    @Transactional(readOnly = true)
    public List<RoleOptionsService.RoleOption> roleOptions() {
        return roleOptions.options();
    }

    @Transactional
    public UserRow create(CreateUser cmd, Principal actor) {
        List<ApiViolation> v = new ArrayList<>();
        String username = cmd.username() == null ? null : cmd.username().trim();
        String display = cmd.displayName() == null ? null : cmd.displayName().trim();
        checkUsername(username, v);
        checkDisplayName(display, v);
        checkPassword(cmd.password(), v);
        Set<String> roles = new LinkedHashSet<>(cmd.roles() == null ? List.of() : cmd.roles());
        checkRoles(roles, Set.of(), v);
        if (!v.isEmpty()) {
            throw failed(v);
        }
        if (users.findByUsername(username).isPresent()) {
            throw taken();
        }
        UUID id = UUID.randomUUID();
        try {
            users.insert(new UserAccount(id, username, encoder.encode(cmd.password()), display,
                    cmd.active() == null || cmd.active(), roles));
        } catch (DuplicateKeyException e) {
            throw taken();
        }
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.put("username", username);
        audit.record(actor.userId(), "create", USER, id.toString(), details);
        return users.findRow(id).orElseThrow();
    }

    @Transactional
    public UserRow update(UUID id, Map<String, Object> patch, Principal actor) {
        UserRow current = users.findRow(id).orElseThrow(() -> new NotFound("User not found"));
        List<ApiViolation> v = new ArrayList<>();
        for (String key : patch.keySet()) {
            if (!key.equals("displayName") && !key.equals("roles") && !key.equals("active")) {
                v.add(new ApiViolation(key, "UNKNOWN_PROPERTY", "Property '" + key + "' cannot be changed here"));
            }
        }
        String display = null;
        if (patch.containsKey("displayName")) {
            Object raw = patch.get("displayName");
            if (raw instanceof String s) {
                display = s.trim();
                checkDisplayName(display, v);
            } else {
                v.add(new ApiViolation("displayName", "TYPE", "displayName must be a string"));
            }
        }
        Boolean active = null;
        if (patch.containsKey("active")) {
            if (patch.get("active") instanceof Boolean b) {
                active = b;
            } else {
                v.add(new ApiViolation("active", "TYPE", "active must be true or false"));
            }
        }
        Set<String> roles = null;
        if (patch.containsKey("roles")) {
            roles = rolesFrom(patch.get("roles"), v);
            if (roles != null) {
                checkRoles(roles, current.roles(), v);
            }
        }
        if (!v.isEmpty()) {
            throw failed(v);
        }
        if (actor.userId() != null && actor.userId().equals(id)) {
            boolean deactivates = Boolean.FALSE.equals(active);
            boolean dropsAdmin = roles != null && current.roles().contains(admin.adminRole())
                    && !roles.contains(admin.adminRole());
            if (deactivates || dropsAdmin) {
                throw new Conflict("SELF_LOCKOUT",
                        "You cannot deactivate yourself or remove your own admin role");
            }
        }
        List<String> changed = new ArrayList<>();
        if (display != null && !display.equals(current.displayName())) {
            changed.add("displayName");
        }
        if (active != null && active != current.active()) {
            changed.add("active");
        }
        if (roles != null && !roles.equals(current.roles())) {
            changed.add("roles");
        }
        if (!changed.isEmpty()) {
            users.update(id, changed.contains("displayName") ? display : null,
                    changed.contains("active") ? active : null,
                    changed.contains("roles") ? roles : null);
            if (changed.contains("active")) {
                activeCache.ifAvailable(c -> c.evict(id));
            }
            ObjectNode details = JsonNodeFactory.instance.objectNode();
            ArrayNode names = details.putArray("changed");
            changed.forEach(names::add);
            audit.record(actor.userId(), "update", USER, id.toString(), details);
        }
        return users.findRow(id).orElseThrow();
    }

    @Transactional
    public void resetPassword(UUID id, String password, Principal actor) {
        users.findRow(id).orElseThrow(() -> new NotFound("User not found"));
        List<ApiViolation> v = new ArrayList<>();
        checkPassword(password, v);
        if (!v.isEmpty()) {
            throw failed(v);
        }
        users.updatePasswordHash(id, encoder.encode(password));
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.putArray("changed").add("password");
        audit.record(actor.userId(), "update", USER, id.toString(), details);
    }

    // ---- validation helpers -------------------------------------------------------------------------------------

    private static int parseInt(String raw, int dflt, int min, int max, String name, List<ApiViolation> bad) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < min || n > max) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            bad.add(new ApiViolation(name, "INVALID", name + " must be an integer >= " + min));
            return dflt;
        }
    }

    private static void checkUsername(String username, List<ApiViolation> v) {
        if (username == null || username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            v.add(new ApiViolation("username", "LENGTH",
                    "username must be " + USERNAME_MIN + " to " + USERNAME_MAX + " characters"));
        } else if (username.chars().anyMatch(Character::isWhitespace)) {
            v.add(new ApiViolation("username", "PATTERN", "username must not contain whitespace"));
        }
    }

    private static void checkDisplayName(String display, List<ApiViolation> v) {
        if (display == null || display.isEmpty() || display.length() > DISPLAY_NAME_MAX) {
            v.add(new ApiViolation("displayName", "LENGTH",
                    "displayName must be 1 to " + DISPLAY_NAME_MAX + " characters"));
        }
    }

    /** Never echoes the password into a message. */
    private static void checkPassword(String password, List<ApiViolation> v) {
        int bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < PASSWORD_MIN_BYTES || bytes > PASSWORD_MAX_BYTES) {
            v.add(new ApiViolation("password", "LENGTH",
                    "password must be " + PASSWORD_MIN_BYTES + " to " + PASSWORD_MAX_BYTES + " bytes"));
        }
    }

    private static Set<String> rolesFrom(Object raw, List<ApiViolation> v) {
        if (!(raw instanceof List<?> list)) {
            v.add(new ApiViolation("roles", "TYPE", "roles must be an array of role codes"));
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s)) {
                v.add(new ApiViolation("roles", "TYPE", "roles must be an array of role codes"));
                return null;
            }
            out.add(s);
        }
        return out;
    }

    /** A code is allowed when it is assignable now, or the user already holds it (so other edits still work). */
    private void checkRoles(Set<String> roles, Set<String> alreadyHeld, List<ApiViolation> v) {
        Set<String> assignable = roleOptions.assignableCodes();
        Set<String> unknown = new TreeSet<>();
        for (String r : roles) {
            if (r == null || !(assignable.contains(r) || alreadyHeld.contains(r))) {
                unknown.add(String.valueOf(r));
            }
        }
        if (!unknown.isEmpty()) {
            v.add(new ApiViolation("roles", "UNKNOWN_ROLE", "Unknown role code(s): " + String.join(", ", unknown)));
        }
    }

    private static ValidationFailed failed(List<ApiViolation> v) {
        return new ValidationFailed("Invalid user: " + String.join(", ", v.stream().map(ApiViolation::field)
                .distinct().toList()), v);
    }

    private static Conflict taken() {
        return new Conflict("USERNAME_TAKEN", "That username is already taken");
    }
}
