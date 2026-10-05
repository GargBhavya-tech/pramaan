/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Measures the stream's real frame rate over the first second of a session (Build Map #14). The SBI
 * specification only guarantees 3 fps, so nothing may assume more than it measures.
 */
public final class FrameRateMeter {

    /** How long to observe before trusting the number. */
    public static final Duration WINDOW = Duration.ofSeconds(1);

    private Instant first;
    private Instant last;
    private long frames;

    /** Records a frame's host arrival time (from the session clock). */
    public void onFrame(Instant arrival) {
        Objects.requireNonNull(arrival, "arrival");
        if (first == null) {
            first = arrival;
        }
        last = arrival;
        frames++;
    }

    /** True once at least one {@link #WINDOW} has been observed with two or more frames. */
    public boolean settled() {
        return frames >= 2 && Duration.between(first, last).compareTo(WINDOW) >= 0;
    }

    /** Frames per second over the observed span, once settled. */
    public OptionalDouble fps() {
        if (!settled()) {
            return OptionalDouble.empty();
        }
        double seconds = Duration.between(first, last).toNanos() / 1e9;
        return OptionalDouble.of((frames - 1) / seconds);
    }
}
