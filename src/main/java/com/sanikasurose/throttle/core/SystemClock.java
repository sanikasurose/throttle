package com.sanikasurose.throttle.core;

/**
 * Production implementation of {@link Clock} that delegates to the JVM system clock.
 *
 * <p>This is the only class in the core package that touches {@code System.currentTimeMillis()}.
 * Isolating that call here means every other class stays fully testable by accepting a
 * {@link Clock} dependency rather than querying time directly.
 */
public final class SystemClock implements Clock {

    /**
     * Returns the current wall-clock time by delegating to {@link System#currentTimeMillis()}.
     *
     * @return current time in milliseconds since midnight, January 1, 1970 UTC
     */
    @Override
    public long currentMillis() {
        return System.currentTimeMillis();
    }
}
