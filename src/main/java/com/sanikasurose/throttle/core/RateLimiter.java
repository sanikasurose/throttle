package com.sanikasurose.throttle.core;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

/**
 * Sliding-window rate limiter backed by a Redis sorted set (ZSET) per user.
 *
 * <p>Each call to {@link #allowRequest(String)} checks whether the given user has
 * exceeded the configured {@link RateLimitPolicy} within the current sliding window.
 * If the user is within the limit the request timestamp is recorded and {@code true}
 * is returned; otherwise no entry is written and {@code false} is returned.
 *
 * <h2>Redis data model</h2>
 * <ul>
 *   <li>Key: {@code throttle:requests:{userId}} — one sorted set per user.</li>
 *   <li>Score: the request timestamp in milliseconds since epoch.</li>
 *   <li>Member: {@code "<timestamp>-<UUID>"} — unique per call to prevent score
 *       collisions when multiple requests arrive within the same millisecond.</li>
 * </ul>
 *
 * <h2>Atomicity</h2>
 * <p>The evict → count → conditional-add sequence must be atomic to prevent
 * over-counting under concurrent load. {@code MULTI/EXEC} and pipelining both
 * queue commands blindly and cannot branch on an intermediate result (the count),
 * so neither is sufficient here. Instead, a <b>Lua script</b> is used: Redis
 * executes Lua scripts as a single atomic unit, guaranteeing that no other client
 * can observe or modify the key between the three operations.
 *
 * <h2>TTL</h2>
 * <p>{@code EXPIRE} is called inside the Lua script (only on allowed requests) to
 * set the key's TTL to {@link RateLimitPolicy#windowSeconds()}. This ensures Redis
 * automatically reclaims memory for inactive users without any background cleanup job.
 */
public final class RateLimiter {

    private static final String KEY_PREFIX = "throttle:requests:";

    /**
     * Lua script executed atomically by Redis on every {@link #allowRequest} call.
     *
     * <p>Script parameters:
     * <ul>
     *   <li>{@code KEYS[1]} — the sorted-set key for the user.</li>
     *   <li>{@code ARGV[1]} — {@code windowStart} in ms: lower bound of the active window
     *       (exclusive); all scores strictly less than this value are evicted via
     *       {@code ZREMRANGEBYSCORE key -inf (windowStart}.</li>
     *   <li>{@code ARGV[2]} — current timestamp in ms, used as the ZSET score.</li>
     *   <li>{@code ARGV[3]} — unique member string ({@code "<ts>-<UUID>"}) for the new entry.</li>
     *   <li>{@code ARGV[4]} — {@link RateLimitPolicy#maxRequests()} as a string.</li>
     *   <li>{@code ARGV[5]} — {@link RateLimitPolicy#windowSeconds()} as a string, used
     *       as the TTL in seconds for the {@code EXPIRE} call.</li>
     * </ul>
     *
     * <p>Returns {@code 1L} if the request is allowed, {@code 0L} if rejected.
     */
    private static final RedisScript<Long> ALLOW_REQUEST_SCRIPT = RedisScript.of("""
            local key         = KEYS[1]
            local windowStart = ARGV[1]
            local nowScore    = ARGV[2]
            local member      = ARGV[3]
            local maxReqs     = tonumber(ARGV[4])
            local ttlSeconds  = tonumber(ARGV[5])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. windowStart)

            local count = redis.call('ZCARD', key)

            if count < maxReqs then
                redis.call('ZADD', key, nowScore, member)
                redis.call('EXPIRE', key, ttlSeconds)
                return 1
            end
            return 0
            """, Long.class);

    private final Clock clock;
    private final RateLimitPolicy policy;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Constructs a {@code RateLimiter} with the supplied clock, policy, and Redis template.
     *
     * @param clock         time source used to determine the current instant; must not be null
     * @param policy        rate-limiting parameters (window and max requests); must not be null
     * @param redisTemplate configured Spring Data Redis template for {@code String} keys and
     *                      values; must not be null
     * @throws IllegalArgumentException if any argument is null
     */
    public RateLimiter(Clock clock, RateLimitPolicy policy, RedisTemplate<String, String> redisTemplate) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate must not be null");
        }
        this.clock = clock;
        this.policy = policy;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Determines whether the given user is permitted to make a request at the current instant.
     *
     * <p>The method delegates all logic to {@link #ALLOW_REQUEST_SCRIPT}, which atomically:
     * <ol>
     *   <li>Evicts timestamps that fall strictly before {@code now - windowMillis} using
     *       {@code ZREMRANGEBYSCORE key -inf (windowStart} (exclusive upper bound).</li>
     *   <li>Counts remaining in-window entries with {@code ZCARD}.</li>
     *   <li>If {@code count < maxRequests}: records the current timestamp via {@code ZADD}
     *       and refreshes the key TTL via {@code EXPIRE}, then returns {@code 1} (allowed).</li>
     *   <li>Otherwise returns {@code 0} (rejected) without writing any entry.</li>
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

        long now = clock.currentMillis();
        long windowStart = now - policy.windowMillis();
        String key = KEY_PREFIX + userId;
        String member = now + "-" + UUID.randomUUID();

        Long result = redisTemplate.execute(
                ALLOW_REQUEST_SCRIPT,
                List.of(key),
                String.valueOf(windowStart),
                String.valueOf(now),
                member,
                String.valueOf(policy.maxRequests()),
                String.valueOf(policy.windowSeconds())
        );

        return Long.valueOf(1L).equals(result);
    }
}
