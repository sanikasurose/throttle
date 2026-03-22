package com.sanikasurose.throttle.web;

import com.sanikasurose.throttle.core.RateLimiter;
import com.sanikasurose.throttle.metrics.RateLimiterMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enforces per-user rate limits on every inbound HTTP request.
 *
 * <p>The filter runs exactly once per request (guaranteed by {@link OncePerRequestFilter}).
 * It reads the caller's identity from the {@value #USER_ID_HEADER} request header,
 * delegates the allow/deny decision to {@link RateLimiter}, records the outcome in
 * {@link RateLimiterMetrics}, and either passes the request down the chain or short-circuits
 * with a 4xx response.
 *
 * <p>Response codes:
 * <ul>
 *   <li>{@code 400 Bad Request} — {@value #USER_ID_HEADER} header is absent or blank.</li>
 *   <li>{@code 429 Too Many Requests} — user has exceeded the configured rate limit.</li>
 *   <li>pass-through — request is within the limit; the filter chain continues normally.</li>
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Name of the HTTP request header from which the caller's identity is read.
     * Clients must include this header on every request; absence results in a
     * {@code 400 Bad Request} response.
     */
    static final String USER_ID_HEADER = "X-User-Id";

    private final RateLimiter rateLimiter;
    private final RateLimiterMetrics metrics;

    /**
     * Constructs the filter with the two collaborators it needs.
     *
     * <p>Spring satisfies both parameters by type from the application context
     * (see {@link ThrottleConfig}); no manual wiring is required.
     *
     * @param rateLimiter the rate-limiter used to make allow/deny decisions; must not be null
     * @param metrics     the metrics collector that records every decision; must not be null
     */
    public RateLimitFilter(RateLimiter rateLimiter, RateLimiterMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /**
     * Exempts internal observability endpoints from rate limiting.
     *
     * <p>{@code /metrics} must remain reachable even when a user is being throttled,
     * so it is excluded from the filter. All other paths, including {@code /ping},
     * are subject to the rate-limit check in {@link #doFilterInternal}.
     *
     * @param request the current HTTP request
     * @return {@code true} if the request path starts with {@code /metrics},
     *         {@code false} for every other path
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/metrics");
    }

    /**
     * Core filter logic executed once per HTTP request.
     *
     * <ol>
     *   <li>Reads {@value #USER_ID_HEADER} from the request.</li>
     *   <li>Returns 400 immediately if the header is missing or blank.</li>
     *   <li>Calls {@link RateLimiter#allowRequest(String)}.</li>
     *   <li>Records the outcome via {@link RateLimiterMetrics#record(String, boolean)}.</li>
     *   <li>Returns 429 with a JSON body if the request was denied; otherwise continues the chain.</li>
     * </ol>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String userId = request.getHeader(USER_ID_HEADER);

        if (userId == null || userId.isBlank()) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                    "{\"error\":\"Missing required header: " + USER_ID_HEADER + "\"}");
            return;
        }

        boolean allowed = rateLimiter.allowRequest(userId);
        metrics.record(userId, allowed);

        if (!allowed) {
            writeJson(response, 429,
                    "{\"error\":\"Rate limit exceeded\",\"userId\":\"" + userId + "\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Writes a JSON response body and short-circuits the filter chain.
     *
     * <p>Sets the HTTP status code, forces {@code Content-Type: application/json; charset=UTF-8},
     * and writes the supplied pre-formatted JSON string directly to the response writer.
     * Called only for error paths (400 and 429); successful requests never reach this method.
     *
     * @param response the current HTTP response; must not be committed yet
     * @param status   the HTTP status code to set (e.g. 400, 429)
     * @param body     a valid JSON string to write as the response body
     * @throws IOException if writing to the response writer fails
     */
    private void writeJson(HttpServletResponse response, int status, String body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
