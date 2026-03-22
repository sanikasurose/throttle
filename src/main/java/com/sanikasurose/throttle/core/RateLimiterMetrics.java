package com.sanikasurose.throttle.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics collector for a {@link RateLimiter}.
 *
 * <p>Tracks two counters per user:
 * <ul>
 *   <li><b>totalRequests</b> — every call to {@link #record(String, boolean)}, allowed or not.</li>
 *   <li><b>rejectedRequests</b> — only calls where {@code allowed} was {@code false}.</li>
 * </ul>
 *
 * <p>Counters are {@link AtomicLong} values stored in {@link ConcurrentHashMap} instances,
 * so individual increments are lock-free. {@link #getSummary()} takes a point-in-time
 * snapshot and therefore does not block ongoing counter updates.
 *
 * <p>Intended usage: call {@link #record(String, boolean)} immediately after each
 * {@link RateLimiter#allowRequest(String)} call, passing the returned boolean as the
 * {@code allowed} argument.
 */
public final class RateLimiterMetrics {

    private final ConcurrentHashMap<String, AtomicLong> totalRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> rejectedRequests = new ConcurrentHashMap<>();

    /**
     * Records the outcome of a single rate-limit decision for the given user.
     *
     * <p>Always increments the total-requests counter for {@code userId}.
     * Additionally increments the rejected-requests counter when {@code allowed}
     * is {@code false}.
     *
     * @param userId  the identifier of the user who made the request; must not be null
     * @param allowed {@code true} if the request was permitted by the rate limiter,
     *                {@code false} if it was rejected
     * @throws IllegalArgumentException if {@code userId} is null
     */
    public void record(String userId, boolean allowed) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }

        totalRequests
                .computeIfAbsent(userId, id -> new AtomicLong())
                .incrementAndGet();

        if (!allowed) {
            rejectedRequests
                    .computeIfAbsent(userId, id -> new AtomicLong())
                    .incrementAndGet();
        }
    }

    /**
     * Returns a point-in-time snapshot of all collected metrics, keyed by user ID.
     *
     * <p>The outer map key is the user ID. Each value is a nested {@code Map} containing:
     * <ul>
     *   <li>{@code "totalRequests"} → {@code long} total number of requests seen for this user</li>
     *   <li>{@code "rejectedRequests"} → {@code long} number of rejected requests for this user</li>
     *   <li>{@code "allowedRequests"} → {@code long} derived value: total minus rejected</li>
     * </ul>
     *
     * <p>Users who have only had allowed requests will not have an entry in the underlying
     * rejected map; in that case the rejected count defaults to zero.
     *
     * @return an unmodifiable-style snapshot map; the returned map and its nested maps
     *         are freshly created and safe to read without synchronization
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new HashMap<>();

        for (String userId : totalRequests.keySet()) {
            long total    = totalRequests.get(userId).get();
            long rejected = rejectedRequests.getOrDefault(userId, new AtomicLong(0L)).get();
            long allowed  = total - rejected;

            Map<String, Long> userStats = new HashMap<>();
            userStats.put("totalRequests",    total);
            userStats.put("rejectedRequests", rejected);
            userStats.put("allowedRequests",  allowed);

            summary.put(userId, userStats);
        }

        return summary;
    }

    /**
     * Returns the total number of requests recorded for the given user,
     * or {@code 0} if the user has not been seen yet.
     *
     * @param userId the user to query; must not be null
     * @return total request count for {@code userId}
     * @throws IllegalArgumentException if {@code userId} is null
     */
    public long getTotalRequests(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        AtomicLong counter = totalRequests.get(userId);
        return counter == null ? 0L : counter.get();
    }

    /**
     * Returns the number of rejected requests recorded for the given user,
     * or {@code 0} if the user has never been rejected (or not yet seen).
     *
     * @param userId the user to query; must not be null
     * @return rejected request count for {@code userId}
     * @throws IllegalArgumentException if {@code userId} is null
     */
    public long getRejectedRequests(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        AtomicLong counter = rejectedRequests.get(userId);
        return counter == null ? 0L : counter.get();
    }
}
