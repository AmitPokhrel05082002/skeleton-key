package dev.skeletonkey.config;

import dev.skeletonkey.web.TrackRateLimitInterceptor;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Rate limiting for the public track endpoint only (Requirement 10). Invalid settings stop the application. */
@Configuration
public class TrackingWebConfig implements WebMvcConfigurer {

    public static final String TRACK_PATH = "/api/public/*/track";

    private final TrackRateLimiter limiter;
    private final TrustedProxies trusted;

    public TrackingWebConfig(@Value("${skeletonkey.track.rate-limit.per-minute:10}") int perMinute,
                             @Value("${skeletonkey.track.rate-limit.max-clients:10000}") int maxClients,
                             @Value("${" + TrustedProxies.PROPERTY + ":" + TrustedProxies.DEFAULT + "}") String proxies,
                             ObjectProvider<Clock> clock) {
        this.limiter = new TrackRateLimiter(perMinute, maxClients, clock.getIfAvailable(Clock::systemUTC));
        this.trusted = TrustedProxies.parse(proxies);
    }

    @Bean
    TrustedProxies trustedProxies() {
        return trusted;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TrackRateLimitInterceptor(limiter, trusted)).addPathPatterns(TRACK_PATH);
    }
}
