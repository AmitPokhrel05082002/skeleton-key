package dev.skeletonkey.app;

import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.BadRequest;
import dev.skeletonkey.web.JwtService;
import dev.skeletonkey.web.LoginRequest;
import dev.skeletonkey.web.LoginResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Login: input validation, BCrypt check with constant-ish timing, JWT issue (Req 6.1, 6.2, 6.3, 6.8). */
@Service
public class AuthService {

    static final int MAX_LEN = 256;
    private static final String BAD_CREDENTIALS = "Invalid credentials";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    /** Hash of a random value, checked when the user is unknown so timing matches the known-user path. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest req) {
        validate(req);
        Optional<UserAccount> found = users.findByUsername(req.username());
        String hash = found.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean matches = hash != null && encoder.matches(req.password(), hash);
        if (found.isEmpty() || !matches || !found.get().active()) {
            // Same exception, same body for unknown user, wrong password and inactive user.
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }
        UserAccount user = found.get();
        return new LoginResponse(jwt.issue(user), "Bearer", jwt.expirySeconds(), user.id(), user.displayName(),
                new ArrayList<>(new TreeSet<>(user.roles())));
    }

    private static void validate(LoginRequest req) {
        List<ApiViolation> v = new ArrayList<>();
        check(v, "username", req == null ? null : req.username());
        check(v, "password", req == null ? null : req.password());
        if (!v.isEmpty()) {
            throw new BadRequest("Invalid login request", v);
        }
    }

    private static void check(List<ApiViolation> v, String field, String value) {
        if (value == null || value.isEmpty()) {
            v.add(new ApiViolation(field, "REQUIRED", field + " must not be empty"));
        } else if (value.length() > MAX_LEN) {
            v.add(new ApiViolation(field, "MAX_LENGTH", field + " must be at most " + MAX_LEN + " characters"));
        }
    }
}
