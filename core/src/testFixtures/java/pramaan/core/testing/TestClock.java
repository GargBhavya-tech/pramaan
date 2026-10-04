/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A {@link Clock} that only moves when a test tells it to (Build Map #8).
 *
 * <p>Every time-dependent API in the engine takes a {@link Clock}, so tests can simulate a 30-second
 * challenge timeout or a 2-4-8 s retry backoff in microseconds of real time, deterministically.
 * Thread-safe: reads and advances are atomic.
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    public TestClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private TestClock(Instant start, ZoneId zone) {
        this.now = Objects.requireNonNull(start, "start");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** A clock starting at an ISO-8601 instant, e.g. {@code "2026-09-22T10:00:00Z"}. */
    public static TestClock at(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant));
    }

    /** Moves time forward. Time never goes backwards; a negative duration is a test bug. */
    public synchronized TestClock advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("a clock cannot go backwards: " + duration);
        }
        now = now.plus(duration);
        return this;
    }

    public TestClock advanceMillis(long millis) {
        return advance(Duration.ofMillis(millis));
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(instant(), newZone);
    }
}
