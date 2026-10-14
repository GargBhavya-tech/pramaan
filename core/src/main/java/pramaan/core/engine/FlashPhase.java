/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashSafety;
import pramaan.core.challenge.FlashSchedule;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.gate.FaceGate;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.FlashReflectionCue;
import pramaan.core.pad.FlashTrace;
import pramaan.core.source.FlashLight;
import pramaan.core.source.Frame;

/**
 * The flash-reflection challenge as an attempt phase (Bible Part 8.6; Build Map #85): plays the plan's random colour
 * sequence on the resident-facing light, records the face's colour in every frame alongside what the light was
 * showing at that frame's time, and hands the trace to {@link FlashReflectionCue} when the sequence and a short tail
 * (for camera lag) are over.
 *
 * <p>Safe by construction: the sequence is re-checked with {@link FlashSafety} before the first colour is shown, and
 * an unsafe one is never displayed — the cue abstains instead. Frames without a single face are not recorded (there
 * is nothing to measure); they do not stop the clock.
 */
final class FlashPhase implements FlashAttemptPhase {

    /** After the last colour, frames continue this long so a camera that lags by a frame or two is still heard. */
    static final Duration TAIL = Duration.ofMillis(600);

    private final FlashLight light;
    private final FlashSequence sequence;
    private final FlashReflectionCue cue = new FlashReflectionCue();
    private FlashSchedule schedule;
    private final FlashTrace trace = new FlashTrace();
    private boolean unsafe;
    private boolean finished;
    private CueResult result;

    /** Time the phase can take: lead-in, every colour, the tail, and a second of slack for frame timing. */
    static long budgetMs(FlashSequence sequence) {
        long ms = FlashSchedule.LEAD_IN.toMillis() + TAIL.toMillis() + 1000;
        for (int d : sequence.durationsMs()) {
            ms += d;
        }
        return ms;
    }

    FlashPhase(FlashLight light, FlashSequence sequence) {
        this.light = light;
        this.sequence = sequence;
    }

    @Override
    public boolean advance(Frame frame, FrameObservation obs) {
        if (finished) {
            return false;
        }
        Instant t = frame.hostArrival();
        if (schedule == null) {
            String violation = FlashSafety.violation(sequence);
            unsafe = violation != null;
            if (unsafe) {
                finish(CueResult.abstain(FlashReflectionCue.NAME, CueResult.AbstainReason.NOT_APPLICABLE));
                return false;
            }
            schedule = new FlashSchedule(sequence, t);
        }
        Optional<FlashColour> colour = schedule.colourAt(t);
        if (colour.isPresent()) {
            light.show(colour.get());
        } else {
            light.off();
        }
        double[][] regions = obs.regions();
        if (regions != null && obs.gate() == FaceGate.Status.ONE_FACE) {
            trace.add(colour, regions[0], regions[1], regions[2]);
        }
        if (!t.isBefore(schedule.start().plus(schedule.total()).plus(TAIL))) {
            finish(cue.evaluate(trace));
            return false;
        }
        return true;
    }

    private void finish(CueResult r) {
        light.off();
        result = r;
        finished = true;
    }

    @Override
    public List<CueResult> results() {
        return result == null ? List.of() : List.of(result);
    }

    @Override
    public void abort() {
        light.off();
    }

    /** True if the sequence was refused as unsafe and nothing was shown. */
    boolean refusedAsUnsafe() {
        return unsafe;
    }
}
