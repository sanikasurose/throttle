package com.sanikasurose.throttle.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Full test suite for {@link RateLimiter}.
 *
 * <p>All time-dependent tests use {@link FakeClock#advance(long)} — there are
 * zero {@code Thread.sleep()} calls in this class.
 *
 * <p>{@link RateLimiter} now delegates its sliding-window logic to a Lua script
 * executed atomically on Redis. There is no Redis server in the unit-test
 * environment, so {@link RedisTemplate} is mocked via Mockito. Each test
 * pre-programs the mock to return {@code 1L} (allowed) or {@code 0L} (rejected)
 * in the exact sequence that Redis would produce given the scenario under test.
 * {@link FakeClock} is still used to control the {@code windowStart} and {@code now}
 * values that the Java layer computes and passes as Lua {@code ARGV} arguments.
 *
 * <p>Standard fixture: 5 requests allowed per 60-second sliding window.
 * The clock starts at 1 000 000 ms so that subtracting {@code windowMillis}
 * never produces a negative {@code windowStart}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimiterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    private FakeClock clock;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_000_000L);
        // Default: Redis allows every request (simulates empty or under-limit window).
        allowAll();
        limiter = new RateLimiter(clock, new RateLimitPolicy(5, 60), redisTemplate);
    }

    // =========================================================================
    // Private helpers — keep stubbing declarations close to the tests that need them
    // =========================================================================

    /**
     * Stubs the Lua script execution to return {@code 1L} (allowed) for all
     * subsequent calls. Used by {@link #setUp()} as the default and by any test
     * whose scenario requires every request to be permitted.
     */
    @SuppressWarnings("unchecked")
    private void allowAll() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(1L);
    }

    /**
     * Stubs the Lua script execution to return the supplied result sequence for
     * successive calls. Once the sequence is exhausted, the final value continues
     * to be returned for all further calls.
     *
     * <p>Usage: {@code scriptReturns(1L, 1L, 1L, 0L)} means the first three
     * invocations return {@code 1L} and the fourth (and any beyond) returns {@code 0L}.
     */
    @SuppressWarnings("unchecked")
    private void scriptReturns(Long first, Long... rest) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(first, (Object[]) rest);
    }

    // =========================================================================
    // T1 — Happy path: requests well inside the limit are allowed
    // =========================================================================

    @Test
    void requestWithinLimit_isAllowed() {
        limiter.allowRequest("alice");
        limiter.allowRequest("alice");

        boolean result = limiter.allowRequest("alice");

        assertThat(result).isTrue();
    }

    // =========================================================================
    // T2 — Boundary: the request that lands exactly on the limit is still allowed
    // =========================================================================

    @Test
    void requestAtExactLimit_isAllowed() {
        for (int i = 0; i < 4; i++) {
            limiter.allowRequest("alice");
        }

        boolean fifthRequest = limiter.allowRequest("alice");

        assertThat(fifthRequest).isTrue();
    }

    // =========================================================================
    // T3 — Rejection: the first request over the limit is rejected
    // =========================================================================

    @Test
    void requestExceedingLimit_isRejected() {
        // Redis allows the first 5, then rejects the 6th.
        scriptReturns(1L, 1L, 1L, 1L, 1L, 0L);

        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("alice");
        }

        boolean sixthRequest = limiter.allowRequest("alice");

        assertThat(sixthRequest).isFalse();
    }

    // =========================================================================
    // T4 — Window expiry: advancing past the full window resets the counter
    // =========================================================================

    @Test
    void windowExpiry_allowsNewRequests() {
        // All 6 calls return 1L. The first 5 fill the window; after clock.advance()
        // the windowStart argument sent to Lua changes so Redis would evict the old
        // entries — the mock simulates that outcome by returning 1L for the 6th call.
        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("alice");
        }

        // 1 ms past the 60-second window: windowStart moves forward by 60 001 ms,
        // which the Lua script uses to evict all previous entries.
        clock.advance(60_001L);

        boolean result = limiter.allowRequest("alice");

        assertThat(result).isTrue();
    }

    // =========================================================================
    // T5 — New user: a user with no history is always allowed on first request
    // =========================================================================

    @Test
    void newUser_isAlwaysAllowed() {
        boolean result = limiter.allowRequest("brand-new-user");

        assertThat(result).isTrue();
    }

    // =========================================================================
    // T6 — Isolation: per-user windows are independent of each other
    // =========================================================================

    @Test
    void multipleUsers_trackedIndependently() {
        // 5 alice calls (1L each) + bob (1L) + alice again (0L) = 7 total
        scriptReturns(1L, 1L, 1L, 1L, 1L, 1L, 0L);

        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("alice");
        }

        // bob's window is untouched — first request must succeed
        assertThat(limiter.allowRequest("bob")).isTrue();
        // alice's window is full — next request must be rejected
        assertThat(limiter.allowRequest("alice")).isFalse();
    }

    // =========================================================================
    // T7 — Partial expiry: only timestamps that have left the window are evicted
    // =========================================================================

    @Test
    void partialWindowExpiry_correctCount() {
        // Tighter policy for more controlled time arithmetic.
        RateLimiter tightLimiter = new RateLimiter(clock, new RateLimitPolicy(3, 10), redisTemplate);

        // Lua result sequence (7 calls total):
        //   calls 1–2: early requests at T → allowed
        //   call  3:   mid-window request at T+5 000 → fills limit → allowed
        //   call  4:   fourth request → rejected (limit full)
        //   calls 5–6: after partial eviction → two slots freed → allowed
        //   call  7:   limit reached again → rejected
        scriptReturns(1L, 1L, 1L, 0L, 1L, 1L, 0L);

        // Phase 1 — two requests at T = 1_000_000
        tightLimiter.allowRequest("alice");
        tightLimiter.allowRequest("alice");

        // Advance half a window (5 s).
        // windowStart = 1_005_000 − 10_000 = 995_000 → both T-requests in scope.
        clock.advance(5_000L);

        // Third request fills the limit; fourth is rejected.
        tightLimiter.allowRequest("alice");
        assertThat(tightLimiter.allowRequest("alice")).isFalse();

        // Advance 5 001 ms more: now = 1_010_001, windowStart = 1_000_001.
        // The two early requests (at 1_000_000) are now strictly before windowStart
        // so Lua evicts them; the mid-window request (at 1_005_000) stays.
        clock.advance(5_001L);

        // Two slots freed — next two requests succeed.
        assertThat(tightLimiter.allowRequest("alice")).isTrue();
        assertThat(tightLimiter.allowRequest("alice")).isTrue();
        // Limit reached again (count = 3).
        assertThat(tightLimiter.allowRequest("alice")).isFalse();
    }

    // =========================================================================
    // T8 — Concurrency: Redis atomicity model handles concurrent allow/reject
    // =========================================================================

    /**
     * 20 threads compete for a single user's quota of 10 requests.
     * A {@link CountDownLatch} holds all threads at the starting line so they
     * are released simultaneously, maximising contention on the shared mock.
     * An {@link AtomicInteger} counter inside the Mockito {@code Answer} allocates
     * exactly 10 {@code 1L} responses and 10 {@code 0L} responses, simulating
     * what Redis would return once the limit is reached. The only correct outcome
     * is exactly 10 allowed and 10 rejected — any other split means the Java
     * layer is not correctly forwarding the Redis decision.
     */
    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequests_noRaceCondition() throws Exception {
        RateLimiter concLimiter = new RateLimiter(clock, new RateLimitPolicy(10, 60), redisTemplate);

        // Atomic counter that simulates Redis granting the first 10 requests.
        AtomicInteger slots = new AtomicInteger(10);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> slots.getAndDecrement() > 0 ? 1L : 0L);

        int threadCount = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger allowed  = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                if (concLimiter.allowRequest("shared-user")) {
                    allowed.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }

        startGate.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);
    }

    // =========================================================================
    // T9 — Exact boundary: the Lua script uses an exclusive lower bound,
    //       so a timestamp equal to windowStart is retained until 1 ms later
    // =========================================================================

    @Test
    void exactBoundaryTimestamp_isEvicted() {
        // Single-request / 1-second window for precise millisecond arithmetic.
        RateLimiter boundaryLimiter = new RateLimiter(clock, new RateLimitPolicy(1, 1), redisTemplate);

        // Lua sequence:
        //   call 1: T=1_000_000 → recorded → allowed (1L)
        //   call 2: T+1_000 → windowStart = T → timestamp T == windowStart
        //           Lua uses '(' (exclusive): scores strictly < windowStart are removed.
        //           T == windowStart → NOT evicted → slot still occupied → rejected (0L)
        //   call 3: T+1_001 → windowStart = T+1 → T < T+1 → IS evicted → allowed (1L)
        scriptReturns(1L, 0L, 1L);

        // Request recorded at T = 1_000_000.
        boundaryLimiter.allowRequest("alice");

        // Advance exactly windowMillis (1 000 ms): windowStart = T → slot retained.
        clock.advance(1_000L);
        assertThat(boundaryLimiter.allowRequest("alice")).isFalse();

        // Advance 1 more ms: windowStart = T+1 → slot evicted.
        clock.advance(1L);
        assertThat(boundaryLimiter.allowRequest("alice")).isTrue();
    }

    // =========================================================================
    // T10 — Metrics integrity: a rejected request must not record a timestamp
    // =========================================================================

    @Test
    void metricsTracked_afterRejection() {
        // Phase 1 (5 allowed, 1 rejected) + Phase 2 after window reset (5 allowed, 1 rejected).
        scriptReturns(1L, 1L, 1L, 1L, 1L, 0L, 1L, 1L, 1L, 1L, 1L, 0L);

        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("alice");
        }

        // This call must return false — and since Redis doesn't record a ZADD on 0L,
        // the window is not inflated.
        assertThat(limiter.allowRequest("alice")).isFalse();

        // Advance past the window; Redis would have evicted all prior entries.
        clock.advance(60_001L);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowRequest("alice")).isTrue();
        }
        assertThat(limiter.allowRequest("alice")).isFalse();
    }

    // =========================================================================
    // Guard-clause tests — invalid arguments must be rejected at the boundary
    // =========================================================================

    @Test
    void nullUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> limiter.allowRequest(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void nullClock_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiter(null, new RateLimitPolicy(5, 60), redisTemplate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clock");
    }

    @Test
    void nullPolicy_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiter(clock, null, redisTemplate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy");
    }

    @Test
    void nullRedisTemplate_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiter(clock, new RateLimitPolicy(5, 60), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisTemplate");
    }
}
