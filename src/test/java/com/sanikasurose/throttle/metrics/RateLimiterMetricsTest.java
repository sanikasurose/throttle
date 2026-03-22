package com.sanikasurose.throttle.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full test suite for {@link RateLimiterMetrics}.
 *
 * <p>No clock dependency exists in this class — all tests are purely
 * synchronous. Each test gets a fresh {@link RateLimiterMetrics} instance
 * from {@link #setUp()} so there is zero shared state between tests.
 */
class RateLimiterMetricsTest {

    private RateLimiterMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new RateLimiterMetrics();
    }

    // =========================================================================
    // record() — counter correctness for a single user
    // =========================================================================

    @Test
    void recordAllowed_incrementsTotalOnly() {
        metrics.record("alice", true);

        assertThat(metrics.getTotalRequests("alice")).isEqualTo(1L);
        assertThat(metrics.getRejectedRequests("alice")).isEqualTo(0L);
    }

    @Test
    void recordRejected_incrementsBothCounters() {
        metrics.record("alice", false);

        assertThat(metrics.getTotalRequests("alice")).isEqualTo(1L);
        assertThat(metrics.getRejectedRequests("alice")).isEqualTo(1L);
    }

    @Test
    void multipleAllowed_totalAccumulates_rejectedStaysZero() {
        metrics.record("alice", true);
        metrics.record("alice", true);
        metrics.record("alice", true);

        assertThat(metrics.getTotalRequests("alice")).isEqualTo(3L);
        assertThat(metrics.getRejectedRequests("alice")).isEqualTo(0L);
    }

    @Test
    void mixedOutcomes_countersTrackCorrectly() {
        // 3 allowed, 2 rejected → total=5, rejected=2
        metrics.record("alice", true);
        metrics.record("alice", true);
        metrics.record("alice", true);
        metrics.record("alice", false);
        metrics.record("alice", false);

        assertThat(metrics.getTotalRequests("alice")).isEqualTo(5L);
        assertThat(metrics.getRejectedRequests("alice")).isEqualTo(2L);
    }

    // =========================================================================
    // Multi-user isolation
    // =========================================================================

    @Test
    void multipleUsers_trackedIndependently() {
        metrics.record("alice", true);
        metrics.record("alice", true);
        metrics.record("alice", false);

        metrics.record("bob", false);
        metrics.record("bob", false);

        assertThat(metrics.getTotalRequests("alice")).isEqualTo(3L);
        assertThat(metrics.getRejectedRequests("alice")).isEqualTo(1L);

        assertThat(metrics.getTotalRequests("bob")).isEqualTo(2L);
        assertThat(metrics.getRejectedRequests("bob")).isEqualTo(2L);
    }

    // =========================================================================
    // getSummary() — structure and content
    // =========================================================================

    @Test
    void getSummary_containsAllThreeKeys() {
        metrics.record("alice", true);

        Map<String, Long> stats = statsFor("alice");

        assertThat(stats).containsKeys("totalRequests", "rejectedRequests", "allowedRequests");
    }

    @Test
    void getSummary_allowedRequestsIsDerivedFromTotalMinusRejected() {
        metrics.record("alice", true);
        metrics.record("alice", true);
        metrics.record("alice", true);
        metrics.record("alice", false);
        metrics.record("alice", false);

        Map<String, Long> stats = statsFor("alice");

        assertThat(stats.get("totalRequests")).isEqualTo(5L);
        assertThat(stats.get("rejectedRequests")).isEqualTo(2L);
        assertThat(stats.get("allowedRequests")).isEqualTo(3L);
    }

    @Test
    void getSummary_userWithNoRejections_hasZeroRejectedCount() {
        // A user who has only ever been allowed must not cause a NullPointerException
        // inside getSummary() when it looks up the rejected map. The count must be 0.
        metrics.record("alice", true);
        metrics.record("alice", true);

        Map<String, Long> stats = statsFor("alice");

        assertThat(stats.get("rejectedRequests")).isEqualTo(0L);
        assertThat(stats.get("allowedRequests")).isEqualTo(2L);
    }

    @Test
    void getSummary_includesAllRecordedUsers() {
        metrics.record("alice", true);
        metrics.record("bob", false);
        metrics.record("carol", true);

        Map<String, Object> summary = metrics.getSummary();

        assertThat(summary).containsKeys("alice", "bob", "carol");
    }

    @Test
    void getSummary_doesNotIncludeUnrecordedUsers() {
        metrics.record("alice", true);

        Map<String, Object> summary = metrics.getSummary();

        assertThat(summary).doesNotContainKey("bob");
    }

    @Test
    void getSummary_returnsFreshSnapshot_subsequentRecordsDoNotMutateIt() {
        metrics.record("alice", true);
        Map<String, Object> snapshot = metrics.getSummary();

        // Two more records arrive after the snapshot was taken.
        metrics.record("alice", false);
        metrics.record("alice", true);

        // getSummary() returns freshly constructed maps — the previously
        // returned snapshot must not reflect any new data.
        @SuppressWarnings("unchecked")
        Map<String, Long> frozenStats = (Map<String, Long>) snapshot.get("alice");
        assertThat(frozenStats.get("totalRequests")).isEqualTo(1L);
    }

    // =========================================================================
    // Direct getters — default values for unseen users
    // =========================================================================

    @Test
    void getTotalRequests_unknownUser_returnsZero() {
        assertThat(metrics.getTotalRequests("nobody")).isEqualTo(0L);
    }

    @Test
    void getRejectedRequests_unknownUser_returnsZero() {
        assertThat(metrics.getRejectedRequests("nobody")).isEqualTo(0L);
    }

    @Test
    void getTotalRequests_afterRecording_isAccurate() {
        metrics.record("alice", true);
        metrics.record("alice", false);
        metrics.record("alice", true);

        assertThat(metrics.getTotalRequests("alice")).isEqualTo(3L);
    }

    @Test
    void getRejectedRequests_afterRecording_isAccurate() {
        metrics.record("alice", true);
        metrics.record("alice", false);
        metrics.record("alice", false);

        assertThat(metrics.getRejectedRequests("alice")).isEqualTo(2L);
    }

    // =========================================================================
    // Concurrency — AtomicLong increments must not be lost under contention
    // =========================================================================

    /**
     * 20 threads simultaneously record 10 calls each (5 allowed, 5 rejected)
     * for the same user.  {@link CountDownLatch} maximises contention on the
     * shared {@link java.util.concurrent.atomic.AtomicLong} counters.
     * The only correct result is total=200 and rejected=100 — any smaller
     * value indicates a lost update in the increment sequence.
     */
    @Test
    void concurrentRecords_noCounterLoss() throws Exception {
        int threadCount = 20;
        int recordsPerThread = 10;
        CountDownLatch startGate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                for (int j = 0; j < recordsPerThread; j++) {
                    // Alternates: even j → allowed, odd j → rejected → 5 each per thread
                    metrics.record("shared-user", j % 2 == 0);
                }
                return null;
            }));
        }

        startGate.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(metrics.getTotalRequests("shared-user")).isEqualTo(200L);
        assertThat(metrics.getRejectedRequests("shared-user")).isEqualTo(100L);
    }

    // =========================================================================
    // Guard-clause tests — null userId must always be rejected
    // =========================================================================

    @Test
    void recordWithNullUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> metrics.record(null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void getTotalRequestsWithNullUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> metrics.getTotalRequests(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void getRejectedRequestsWithNullUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> metrics.getRejectedRequests(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Calls {@link RateLimiterMetrics#getSummary()} and returns the inner stat
     * map for {@code userId}, casting it to {@code Map<String, Long>}.
     * Centralises the unchecked cast so individual tests stay readable.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> statsFor(String userId) {
        return (Map<String, Long>) metrics.getSummary().get(userId);
    }
}
