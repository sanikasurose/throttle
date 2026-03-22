package com.sanikasurose.throttle.core;

import java.util.ArrayDeque;

/**
 * Mutable, per-user sliding-window request log.
 *
 * <p>Holds an ordered queue of timestamps (in milliseconds) representing each request
 * made by a single user. The queue grows as new requests arrive and shrinks as old
 * timestamps fall outside the current window via {@link #evictOld(long)}.
 *
 * <p><strong>Thread safety:</strong> this class is not thread-safe on its own.
 * Callers must synchronize on the {@code RequestRecord} instance before invoking
 * any method, keeping the lock as narrow as possible (per-user, not global).
 */
public final class RequestRecord {

    private final ArrayDeque<Long> timestamps = new ArrayDeque<>();

    /**
     * Records a new request by appending the given timestamp to the tail of the queue.
     *
     * @param timestampMillis the time of the request in milliseconds since epoch
     */
    public void add(long timestampMillis) {
        timestamps.addLast(timestampMillis);
    }

    /**
     * Removes all timestamps that fall strictly before {@code windowStart}, discarding
     * requests that are no longer relevant to the current sliding window.
     *
     * <p>Because timestamps are appended in chronological order, the oldest entries
     * are always at the head of the deque, so this is an O(k) operation where
     * {@code k} is the number of expired entries — not O(n) over the whole queue.
     *
     * @param windowStart the earliest timestamp (inclusive) that should be retained,
     *                    expressed in milliseconds since epoch
     */
    public void evictOld(long windowStart) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }
    }

    /**
     * Returns the number of requests currently tracked within the active window.
     *
     * <p>This count is only meaningful after {@link #evictOld(long)} has been called
     * to remove expired entries for the current evaluation instant.
     *
     * @return number of in-window request timestamps
     */
    public int count() {
        return timestamps.size();
    }
}
