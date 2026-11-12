package dev.skeletonkey.web;

import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Issues and verifies HS256 JWTs carrying the user id ({@code sub}), {@code roles} and {@code exp} (Req 6.1, 6.4). */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;
    private final Clock clock;

    @Autowired
    public JwtService(SkeletonProperties props) {
        this(props.jwt().secret(), props.jwt().expirySeconds(), Clock.systemUTC());
    }

    public JwtService(String secret, long expirySeconds, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
        this.clock = clock;
    }

    public String issue(UserAccount user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.id().toString())
                .claim("roles", new TreeSet<>(user.roles()).stream().toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long expirySeconds() {
        return expirySeconds;
    }

    /** Verifies signature (HS256 only) and expiry; empty for any missing, expired, tampered or malformed token. */
    public Optional<Principal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID id = UUID.fromString(claims.getSubject());
            Set<String> roles = new HashSet<>();
            Object raw = claims.get("roles");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s) {
                        roles.add(s);
                    }
                }
            }
            return Optional.of(new Principal(id, roles));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
