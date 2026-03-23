package com.sanikasurose.throttle.web;

import com.sanikasurose.throttle.core.Clock;
import com.sanikasurose.throttle.core.RateLimitPolicy;
import com.sanikasurose.throttle.core.RateLimiter;
import com.sanikasurose.throttle.core.SystemClock;
import com.sanikasurose.throttle.metrics.RateLimiterMetrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Spring configuration that wires all Throttle core objects into the
 * application context as singletons.
 *
 * <p>Placing the bean definitions here — rather than annotating the core
 * classes directly — keeps the core package free of Spring dependencies
 * and independently testable.
 */
@Configuration
public class ThrottleConfig {

    /**
     * Exposes {@link SystemClock} as the application's {@link Clock} implementation.
     * Any bean that needs a {@code Clock} will receive this singleton.
     */
    @Bean
    public Clock clock() {
        return new SystemClock();
    }

    /**
     * Default rate-limit policy: 5 requests per 10-second sliding window.
     * Adjust the constructor arguments here to tune the limit globally.
     */
    @Bean
    public RateLimitPolicy rateLimitPolicy() {
        return new RateLimitPolicy(5, 10);
    }

    /**
     * Creates the {@link RateLimiter} singleton, injecting the clock, policy, and Redis
     * template beans. Spring Boot auto-configures a {@code StringRedisTemplate}
     * (a {@code RedisTemplate<String, String>}) when {@code spring-boot-starter-data-redis}
     * is on the classpath; Spring resolves all three parameters by type.
     */
    @Bean
    public RateLimiter rateLimiter(Clock clock, RateLimitPolicy policy,
                                   RedisTemplate<String, String> redisTemplate) {
        return new RateLimiter(clock, policy, redisTemplate);
    }

    /**
     * Creates the {@link RateLimiterMetrics} singleton.
     * Shared by both the filter (writes) and the metrics endpoint (reads).
     */
    @Bean
    public RateLimiterMetrics rateLimiterMetrics() {
        return new RateLimiterMetrics();
    }
}
