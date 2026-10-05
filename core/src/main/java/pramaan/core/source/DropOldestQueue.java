/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * A bounded hand-off between a producer that must never block (the device stream) and a consumer that may
 * fall behind (the engine). When full, the <em>oldest</em> item is dropped: liveness cares about now,
 * and unbounded buffering is how a slow consumer turns into an out-of-memory crash (Build Map #13).
 */
public final class DropOldestQueue<T> {

    private final int capacity;
    private final Deque<T> items = new ArrayDeque<>();
    private long dropped;
    private boolean closed;

    public DropOldestQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    /** Adds an item, dropping the oldest if full. Never blocks. Returns false if the queue is closed. */
    public synchronized boolean offer(T item) {
        if (closed) {
            return false;
        }
        if (items.size() == capacity) {
            items.removeFirst();
            dropped++;
        }
        items.addLast(item);
        notifyAll();
        return true;
    }

    /**
     * Waits up to about the timeout for an item; null if none arrived or the queue is closed and empty.
     * A single bounded wait, not a deadline loop: callers poll in a loop anyway, and core logic never
     * reads the system clock (Build Map #8).
     */
    public synchronized T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (items.isEmpty() && !closed) {
            wait(Math.max(1, unit.toMillis(timeout)));
        }
        return items.pollFirst();
    }

    /** Stops accepting items and wakes waiting consumers; items already queued can still be drained. */
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized long droppedCount() {
        return dropped;
    }
}
