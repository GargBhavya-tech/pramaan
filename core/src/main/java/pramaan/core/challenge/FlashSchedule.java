/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When each colour of a {@link FlashSequence} is on screen (Bible Part 8.6; Build Map #85). The light is off for
 * {@link #LEAD_IN} before the first colour, so the cue has an ambient reference, and off again afterwards. Time is
 * always a caller-supplied instant, so the schedule is testable without a clock.
 */
public final class FlashSchedule {

    /** Dark (ambient-only) interval before the first colour. */
    public static final Duration LEAD_IN = Duration.ofMillis(500);

    private final FlashSequence sequence;
    private final Instant start;
    private final long[] endsMs;

    /** @param start the instant the lead-in begins; the first colour appears at {@code start + LEAD_IN} */
    public FlashSchedule(FlashSequence sequence, Instant start) {
        this.sequence = java.util.Objects.requireNonNull(sequence, "sequence");
        this.start = java.util.Objects.requireNonNull(start, "start");
        int[] d = sequence.durationsMs();
        endsMs = new long[d.length];
        long t = LEAD_IN.toMillis();
        for (int i = 0; i < d.length; i++) {
            t += d[i];
            endsMs[i] = t;
        }
    }

    /** The colour on screen at {@code t}, or empty while the light is off (lead-in, or after the last colour). */
    public Optional<FlashColour> colourAt(Instant t) {
        long ms = Duration.between(start, t).toMillis();
        if (ms < LEAD_IN.toMillis() || ms >= total().toMillis()) {
            return Optional.empty();
        }
        for (int i = 0; i < endsMs.length; i++) {
            if (ms < endsMs[i]) {
                return Optional.of(sequence.colours().get(i));
            }
        }
        return Optional.empty();
    }

    /** From {@code start} to the moment the last colour ends. */
    public Duration total() {
        return Duration.ofMillis(endsMs.length == 0 ? LEAD_IN.toMillis() : endsMs[endsMs.length - 1]);
    }

    public Instant start() {
        return start;
    }

    public FlashSequence sequence() {
        return sequence;
    }
}
