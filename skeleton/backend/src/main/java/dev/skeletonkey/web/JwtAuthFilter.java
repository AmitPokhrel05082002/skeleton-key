package dev.skeletonkey.web;

import dev.skeletonkey.domain.model.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer} and, when the token is valid, sets the Spring Security context to the domain
 * {@link Principal}. An invalid token leaves the request unauthenticated, so protected routes answer 401 through the
 * authentication entry point (Req 6.4). Not a Spring bean: it is added to the security chain only.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            jwt.parse(header.substring(PREFIX.length()).trim()).ifPresent(p -> {
                List<SimpleGrantedAuthority> authorities =
                        p.roles().stream().sorted().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(p, null, authorities));
            });
        }
        chain.doFilter(req, res);
    }
}
