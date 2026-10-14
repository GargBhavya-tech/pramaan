/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.PupilLightReflexCue;
import pramaan.core.source.Frame;
import pramaan.core.source.PupilSource;

/**
 * The pupil-light-reflex challenge as an attempt phase (Bible Part 32; Build Map #90): fires a short visible flash
 * three times at random instants, with gaps of 1.5-3 s drawn from a CSPRNG so a recording cannot anticipate them, then
 * asks the iris camera for the pupil radii over the whole window and gives them to {@link PupilLightReflexCue}.
 *
 * <p>The resident is asked for nothing: it works for infants and for residents who cannot follow prompts. The cue is
 * positive-only — a reflex that is absent, or not time-locked to every flash, abstains and never counts against
 * anyone.
 */
final class PupilPhase implements AttemptPhase {

    static final int FLASHES = 3;
    static final long FIRST_FLASH_DELAY_MS = 800;
    /**
     * Gap between flashes: 2.0-3.5 s, drawn uniformly. The Bible says 1.5-3 s, but a pupil that has constricted for a
     * flash is still partly constricted 1.5 s later, which depresses the baseline the next flash is measured against
     * and shrinks its apparent amplitude; the longer minimum lets it recover. Still unpredictable to a recording.
     */
    static final long GAP_MIN_MS = 2000;
    static final long GAP_SPAN_MS = 1500;
    /** The response is read this long after the last flash: constriction and the start of re-dilation. */
    static final long LAST_WINDOW_MS = 1700;

    /** Longest the phase can take: first delay, two maximum gaps, the response window, and a second of slack. */
    static final long WORST_CASE_MS = FIRST_FLASH_DELAY_MS + 2 * (GAP_MIN_MS + GAP_SPAN_MS) + LAST_WINDOW_MS + 1000;

    private final PupilSource source;
    private final SecureRandom random;
    private final PupilLightReflexCue cue = new PupilLightReflexCue();
    private long startMs = -1;
    private final long[] offsets = new long[FLASHES];
    private final double[] fired = new double[FLASHES];
    private int firedCount;
    private boolean finished;
    private CueResult result;

    PupilPhase(PupilSource source, SecureRandom random) {
        this.source = source;
        this.random = random;
    }

    @Override
    public boolean advance(Frame frame, FrameObservation obs) {
        if (finished) {
            return false;
        }
        long now = frame.hostArrival().toEpochMilli();
        if (startMs < 0) {
            startMs = now;
            long at = FIRST_FLASH_DELAY_MS;
            for (int i = 0; i < FLASHES; i++) {
                offsets[i] = at;
                at += GAP_MIN_MS + (long) (random.nextDouble() * GAP_SPAN_MS);
            }
        }
        if (firedCount < FLASHES && now - startMs >= offsets[firedCount]) {
            source.stimulate();
            fired[firedCount++] = now;
        }
        if (firedCount == FLASHES && now - fired[FLASHES - 1] >= LAST_WINDOW_MS) {
            finished = true;
            result = judge(now);
            return false;
        }
        return true;
    }

    private CueResult judge(long now) {
        double[][] rows = source.radii(startMs, now);
        if (rows == null || rows.length == 0) {
            return CueResult.abstain(PupilLightReflexCue.NAME, CueResult.AbstainReason.CAPABILITY_MISSING);
        }
        double[] t = new double[rows.length];
        double[] r = new double[rows.length];
        for (int i = 0; i < rows.length; i++) {
            t[i] = rows[i][0];
            r[i] = rows[i][1];
        }
        return cue.evaluate(t, r, fired.clone());
    }

    @Override
    public List<CueResult> results() {
        List<CueResult> out = new ArrayList<>();
        if (result != null) {
            out.add(result);
        }
        return out;
    }

    @Override
    public void abort() {
        // Nothing to switch off: each flash is a self-timed pulse.
    }
}
