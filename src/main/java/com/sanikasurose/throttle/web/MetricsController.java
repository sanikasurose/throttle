package com.sanikasurose.throttle.web;

import com.sanikasurose.throttle.metrics.RateLimiterMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller that exposes observability endpoints for the Throttle service.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /metrics} — returns a per-user snapshot of rate-limit counters as JSON.</li>
 *   <li>{@code GET /ping}    — lightweight liveness check; always returns {@code 200 OK}.</li>
 * </ul>
 */
@RestController
public class MetricsController {

    private final RateLimiterMetrics metrics;

    public MetricsController(RateLimiterMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Returns a point-in-time snapshot of all collected rate-limit metrics, keyed by user ID.
     *
     * <p>Each user entry contains:
     * <ul>
     *   <li>{@code totalRequests}    — total requests seen for that user</li>
     *   <li>{@code allowedRequests}  — requests that passed the rate limit</li>
     *   <li>{@code rejectedRequests} — requests that were throttled</li>
     * </ul>
     *
     * @return {@code 200 OK} with the metrics map serialised as JSON by Jackson
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(metrics.getSummary());
    }

    /**
     * Simple liveness probe.
     *
     * <p>Returns {@code 200 OK} with a plain JSON body. Useful for load-balancer
     * health checks and smoke-testing that the application has started correctly.
     *
     * @return {@code 200 OK} with {@code {"status":"ok"}}
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
