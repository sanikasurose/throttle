package com.sanikasurose.throttle.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full test suite for {@link RateLimiter}.
 *
 * <p>All time-dependent tests use {@link FakeClock#advance(long)} — there are
 * zero {@code Thread.sleep()} calls in this class.
 *
 * <p>Standard fixture: 5 requests allowed per 60-second sliding window.
 * The clock starts at 1 000 000 ms so that subtracting {@code windowMillis}
 * never produces a negative {@code windowStart}, avoiding any accidental
 * edge-cases at epoch-zero.
 */
class RateLimiterTest {

    private FakeClock clock;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_000_000L);
        limiter = new RateLimiter(clock, new RateLimitPolicy(5, 60));
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
        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("alice");
        }

        // 1 ms past the 60-second window: every recorded timestamp falls
        // strictly before the new windowStart and is evicted.
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
        RateLimiter tightLimiter = new RateLimiter(clock, new RateLimitPolicy(3, 10));

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
        // The two early requests (at 1_000_000) < 1_000_001 → evicted.
        // The mid-window request (at 1_005_000) >= 1_000_001 → retained; count = 1.
        clock.advance(5_001L);

        // Two slots freed — next two requests succeed.
        assertThat(tightLimiter.allowRequest("alice")).isTrue();
        assertThat(tightLimiter.allowRequest("alice")).isTrue();
        // Limit reached again (count = 3).
        assertThat(tightLimiter.allowRequest("alice")).isFalse();
    }

    // =========================================================================
    // T8 — Concurrency: the synchronized-per-record design prevents over-counting
    // =========================================================================

    /**
     * 20 threads compete for a single user's quota of 10 requests.
     * A {@link CountDownLatch} holds all threads at the starting line so they
     * are released simultaneously, maximising lock contention on the shared
     * {@code RequestRecord}.  The only correct outcome is exactly 10 allowed
     * and 10 rejected — any other split would indicate a lost-update bug.
     */
    @Test
    void concurrentRequests_noRaceCondition() throws Exception {
        RateLimiter concLimiter = new RateLimiter(clock, new RateLimitPolicy(10, 60));

        int threadCount = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
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
    // T9 — Exact boundary: eviction uses strict less-than, so a timestamp
    //       equal to windowStart is retained; only timestamps strictly before
    //       windowStart are evicted
    // =========================================================================

    @Test
    void exactBoundaryTimestamp_isEvicted() {
        // Single-request / 1-second window for precise millisecond arithmetic.
        RateLimiter boundaryLimiter = new RateLimiter(clock, new RateLimitPolicy(1, 1));

        // Request recorded at T = 1_000_000.
        boundaryLimiter.allowRequest("alice");

        // Advance exactly windowMillis (1 000 ms).
        // now = 1_001_000; windowStart = 1_001_000 − 1_000 = 1_000_000 = T.
        // evictOld removes timestamps strictly < windowStart.
        // T == windowStart → NOT evicted → slot still occupied.
        clock.advance(1_000L);
        assertThat(boundaryLimiter.allowRequest("alice")).isFalse();

        // Advance 1 more ms.
        // now = 1_001_001; windowStart = 1_001_001 − 1_000 = 1_000_001 = T + 1.
        // T < T+1 → IS evicted → slot freed.
        clock.advance(1L);
        assertThat(boundaryLimiter.allowRequest("alice")).isTrue();
    }

    // =========================================================================
    // T10 — Metrics integrity: a rejected request must not record a timestamp
    // =========================================================================

    @Test
    void metricsTracked_afterRejection() {
        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("alice");
        }

        // This call must return false and must NOT add a timestamp.
        assertThat(limiter.allowRequest("alice")).isFalse();

        // If the rejection had leaked a timestamp, the new window after expiry
        // would contain a phantom entry and the 5th request below would fail.
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
        assertThatThrownBy(() -> new RateLimiter(null, new RateLimitPolicy(5, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clock");
    }

    @Test
    void nullPolicy_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiter(clock, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy");
    }
}
