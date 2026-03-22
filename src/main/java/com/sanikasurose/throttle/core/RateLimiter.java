package com.sanikasurose.throttle.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter that tracks per-user request counts.
 *
 * <p>Each call to {@link #allowRequest(String)} checks whether the given user has
 * exceeded the configured {@link RateLimitPolicy} within the current sliding window.
 * If the user is within the limit the request is recorded and {@code true} is returned;
 * otherwise no timestamp is recorded and {@code false} is returned.
 *
 * <p><strong>Concurrency model:</strong> the map of {@link RequestRecord} instances is a
 * {@link ConcurrentHashMap}, so map-level reads and writes are thread-safe. However,
 * the per-user check-then-act sequence (evict → count → conditionally add) must be
 * atomic, so each {@link RequestRecord} is used as its own lock via {@code synchronized}.
 * This means two threads serving different users never block each other.
 */
public final class RateLimiter {

    private final Clock clock;
    private final RateLimitPolicy policy;
    private final ConcurrentHashMap<String, RequestRecord> records = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code RateLimiter} with the supplied clock and policy.
     *
     * @param clock  time source used to determine the current instant; must not be null
     * @param policy rate-limiting parameters (window and max requests); must not be null
     * @throws IllegalArgumentException if either argument is null
     */
    public RateLimiter(Clock clock, RateLimitPolicy policy) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.clock = clock;
        this.policy = policy;
    }

    /**
     * Determines whether the given user is permitted to make a request at the current instant.
     *
     * <p>The method:
     * <ol>
     *   <li>Looks up (or lazily creates) the {@link RequestRecord} for {@code userId}.</li>
     *   <li>Synchronizes on that record to ensure atomicity of the check-then-act sequence.</li>
     *   <li>Evicts timestamps that have fallen outside the sliding window.</li>
     *   <li>If the remaining count is below {@link RateLimitPolicy#maxRequests()}, records
     *       the current timestamp and returns {@code true}.</li>
     *   <li>Otherwise returns {@code false} without recording the request.</li>
     * </ol>
     *
     * @param userId a non-null identifier for the caller (e.g. API key, username, IP address)
     * @return {@code true} if the request is within the allowed rate; {@code false} if it
     *         has been rejected due to exceeding the limit
     * @throws IllegalArgumentException if {@code userId} is null
     */
    public boolean allowRequest(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }

        RequestRecord record = records.computeIfAbsent(userId, id -> new RequestRecord());

        synchronized (record) {
            long now = clock.currentMillis();
            long windowStart = now - policy.windowMillis();

            record.evictOld(windowStart);

            if (record.count() < policy.maxRequests()) {
                record.add(now);
                return true;
            }

            return false;
        }
    }
}
