package com.sanikasurose.throttle.core;

/**
 * Immutable value object describing a rate-limiting policy.
 *
 * <p>A policy combines two values: how many requests are permitted ({@code maxRequests})
 * and the duration of the sliding window over which that limit is measured
 * ({@code windowSeconds}). Using a Java record guarantees immutability and provides
 * {@code equals}, {@code hashCode}, and {@code toString} for free.
 *
 * @param maxRequests   the maximum number of requests allowed within the window;
 *                      must be greater than zero
 * @param windowSeconds the length of the sliding time window in seconds;
 *                      must be greater than zero
 */
public record RateLimitPolicy(int maxRequests, long windowSeconds) {

    /**
     * Compact canonical constructor that validates both fields on construction.
     *
     * @throws IllegalArgumentException if {@code maxRequests} or {@code windowSeconds}
     *                                  is not a positive value
     */
    public RateLimitPolicy {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException(
                    "maxRequests must be greater than zero, got: " + maxRequests);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "windowSeconds must be greater than zero, got: " + windowSeconds);
        }
    }

    /**
     * Returns the window duration converted to milliseconds, suitable for direct
     * comparison against {@link Clock#currentMillis()} timestamps.
     *
     * @return window length in milliseconds
     */
    public long windowMillis() {
        return windowSeconds * 1_000L;
    }
}
