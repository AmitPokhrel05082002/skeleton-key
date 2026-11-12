package dev.skeletonkey.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.web.ApiError;
import dev.skeletonkey.web.JwtAuthFilter;
import dev.skeletonkey.web.JwtService;
import dev.skeletonkey.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless Spring Security with JWT. Authorisation per action is decided in the engine against the definition; this
 * chain only separates anonymous routes (login, public subset, form submit) from authenticated ones (Req 6.4, 6.5,
 * 6.7). 401 and 403 use the common {@link ApiError} body.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, ObjectMapper mapper)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint(mapper))
                .accessDeniedHandler(accessDenied(mapper)))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/apps/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/apps/*/forms/*/submit").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (req, res, ex) ->
                write(mapper, res, new ApiError(401, "UNAUTHENTICATED", "Authentication required",
                        MDC.get(RequestIdFilter.MDC_KEY), List.of()));
    }

    private static AccessDeniedHandler accessDenied(ObjectMapper mapper) {
        return (req, res, ex) ->
                write(mapper, res, new ApiError(403, "FORBIDDEN", "Access denied",
                        MDC.get(RequestIdFilter.MDC_KEY), List.of()));
    }

    private static void write(ObjectMapper mapper, HttpServletResponse res, ApiError body) throws IOException {
        res.setStatus(body.status());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), body);
    }
}
