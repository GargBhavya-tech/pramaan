/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pramaan.core.binding.EmissionLog;
import pramaan.core.challenge.FlashSafety;
import pramaan.core.challenge.FlashSchedule;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.gate.FaceGate;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.FlashReflectionCue;
import pramaan.core.pad.FlashTrace;
import pramaan.core.source.DeviceIlluminator;
import pramaan.core.source.Frame;

/**
 * The flash challenge played by the SBI device's own illuminator (Liveness Profile, addition 5): frames are kept
 * through a dark lead-in, the schedule is sent, and when the device returns its signed emission log the frames are
 * lined up against what the light <em>actually</em> did rather than against what was asked. Everything else — the
 * cue, the safety gate, the tail — is the same as {@link FlashPhase}.
 *
 * <p>A device that cannot be reached, fails, or returns a log that differs from the schedule makes the cue abstain and
 * leaves a {@link #problem()} for the audit trail; it never counts against the resident.
 */
final class DeviceFlashPhase implements FlashAttemptPhase {

    /** A step may differ from the request by this much before the log is treated as another schedule. */
    static final long LOG_TOLERANCE_MS = 150;
    private static final long LOG_WAIT_SECONDS = 2;

    private final DeviceIlluminator illuminator;
    private final FlashSequence sequence;
    private final FlashReflectionCue cue = new FlashReflectionCue();
    private final List<Instant> times = new ArrayList<>();
    private final List<double[][]> regions = new ArrayList<>();
    private Instant start;
    private Instant requestedAt;
    private CompletableFuture<EmissionLog> pending;
    private boolean finished;
    private CueResult result;
    private String problem;

    DeviceFlashPhase(DeviceIlluminator illuminator, FlashSequence sequence) {
        this.illuminator = illuminator;
        this.sequence = sequence;
    }

    @Override
    public boolean advance(Frame frame, FrameObservation obs) {
        if (finished) {
            return false;
        }
        Instant t = frame.hostArrival();
        if (start == null) {
            String violation = FlashSafety.violation(sequence);
            if (violation != null) {
                problem = "refused an unsafe sequence: " + violation;
                finish(CueResult.abstain(FlashReflectionCue.NAME, CueResult.AbstainReason.NOT_APPLICABLE));
                return false;
            }
            start = t;
        }
        if (obs.regions() != null && obs.gate() == FaceGate.Status.ONE_FACE) {
            times.add(t);
            regions.add(obs.regions());
        }
        if (pending == null && !t.isBefore(start.plus(FlashSchedule.LEAD_IN))) {
            requestedAt = t; // the anchor for the log: the host clock when the schedule was sent
            pending = illuminator.illuminate(sequence);
        }
        if (pending != null && !t.isBefore(requestedAt.plus(Duration.ofMillis(total())).plus(FlashPhase.TAIL))) {
            finish(evaluate());
            return false;
        }
        return true;
    }

    private long total() {
        long ms = 0;
        for (int d : sequence.durationsMs()) {
            ms += d;
        }
        return ms;
    }

    private CueResult evaluate() {
        EmissionLog log;
        try {
            log = pending.get(LOG_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            problem = "the device gave no emission log: " + (e.getCause() != null ? e.getCause().getMessage() : "timed out");
            return CueResult.abstain(FlashReflectionCue.NAME, CueResult.AbstainReason.NOT_APPLICABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem = "interrupted while waiting for the emission log";
            return CueResult.abstain(FlashReflectionCue.NAME, CueResult.AbstainReason.NOT_APPLICABLE);
        }
        String difference = log.differenceFrom(sequence, LOG_TOLERANCE_MS);
        if (difference != null) {
            problem = "the emission log differs from the schedule: " + difference;
            return CueResult.abstain(FlashReflectionCue.NAME, CueResult.AbstainReason.NOT_APPLICABLE);
        }
        FlashTrace trace = new FlashTrace();
        for (int i = 0; i < times.size(); i++) {
            double[][] r = regions.get(i);
            trace.add(log.colourAt(requestedAt, times.get(i)), r[0], r[1], r[2]);
        }
        return cue.evaluate(trace);
    }

    private void finish(CueResult r) {
        result = r;
        finished = true;
    }

    @Override
    public List<CueResult> results() {
        return result == null ? List.of() : List.of(result);
    }

    @Override
    public void abort() {
        // The device's schedule cannot be recalled; nothing here holds a light on.
        finished = true;
    }

    /** Why the cue abstained instead of judging, or null. For the audit trail. */
    String problem() {
        return problem;
    }
}
