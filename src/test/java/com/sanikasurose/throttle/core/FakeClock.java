package com.sanikasurose.throttle.core;

/**
 * Test-only implementation of {@link Clock} whose notion of "now" is entirely
 * under the caller's control.
 *
 * <p>Inject a {@code FakeClock} wherever a {@link Clock} is required in tests
 * and call {@link #advance(long)} to move time forward deterministically.
 * This eliminates every {@code Thread.sleep()} call from the test suite while
 * keeping time-dependent assertions fully precise.
 *
 * <p>The {@code time} field is {@code volatile} so that concurrent tests (e.g.
 * T8) that read the clock from multiple threads see a consistent value even
 * though the field may have been written by the main test thread before the
 * worker threads were spawned.
 */
public class FakeClock implements Clock {

    private volatile long time;

    public FakeClock(long startTime) {
        this.time = startTime;
    }

    /** Moves the clock forward by {@code millis} milliseconds. */
    public void advance(long millis) {
        this.time += millis;
    }

    @Override
    public long currentMillis() {
        return time;
    }
}
