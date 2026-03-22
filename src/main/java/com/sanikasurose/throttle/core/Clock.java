package com.sanikasurose.throttle.core;

/**
 * Abstraction over the system clock.
 *
 * <p>Decoupling time retrieval behind this interface allows tests to inject
 * a fake clock and control exactly what "now" means, making time-sensitive
 * rate-limit logic fully deterministic without {@code Thread.sleep}.
 */
public interface Clock {

    /**
     * Returns the current time as a Unix epoch timestamp in milliseconds.
     *
     * @return current time in milliseconds since midnight, January 1, 1970 UTC
     */
    long currentMillis();
}
