/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Bible Part 32 and Build Map #90: PupilReflexFitTest, PlrTimingLockTest, and the positive-only rule. */
class PupilLightReflexCueTest {

    private static final double FRAME_MS = 33; // 30 fps NIR camera
    private static final double DURATION_MS = 9000;
    private static final double[] FLASHES = {1500, 4200, 6900};

    private static double[] times() {
        int n = (int) (DURATION_MS / FRAME_MS);
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * FRAME_MS;
        }
        return t;
    }

    /** Radius with a constriction after each of {@code responseTo}, plus segmentation noise. */
    private static double[] radius(double[] t, double r0, double amplitude, double latency, double tau,
            double[] responseTo, double sigma, long seed) {
        Random noise = new Random(seed);
        double[] r = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            double drop = 0;
            for (double ts : responseTo) {
                double rel = t[i] - ts - latency;
                if (rel >= 0) {
                    // Constriction, then a slow re-dilation (Part 32) so one response never leaks into the next.
                    double d = amplitude * (1 - Math.exp(-rel / tau)) * (rel > 1200 ? Math.exp(-(rel - 1200) / 400) : 1);
                    drop = Math.max(drop, d);
                }
            }
            r[i] = r0 * (1 - drop) + noise.nextGaussian() * sigma;
        }
        return r;
    }

    @Test
    void theBiblesWorkedExampleIsRecoveredAndPasses() {
        double[] t = times();
        double[] r = radius(t, 42, 0.26, 280, 300, FLASHES, 0.25, 1);
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, FLASHES);
        assertTrue(a.reflexPresent);
        for (PupilLightReflexCue.Trial trial : a.trials) {
            assertEquals(280, trial.latencyMs, 40, "latency");
            assertEquals(0.26, trial.amplitude, 0.04, "amplitude");
            assertTrue(trial.r2 > 0.7, "R^2 " + trial.r2);
        }
        assertFalse(a.result.abstained());
        assertTrue(a.result.pLive() > 0.9);
    }

    @Test
    void aPrintedIrisHasNoAmplitudeAndNeverVotesAgainstAnyone() {
        double[] t = times();
        double[] r = radius(t, 42, 0, 280, 300, FLASHES, 0.15, 2);
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, FLASHES);
        assertFalse(a.reflexPresent);
        for (PupilLightReflexCue.Trial trial : a.trials) {
            assertTrue(trial.amplitude < 0.03, "amplitude " + trial.amplitude);
        }
        assertTrue(a.result.abstained(), "absence is never evidence against a person");
    }

    @Test
    void aConstrictionAtTheWrongTimeIsNotTimeLockedToTheRandomFlashes() {
        double[] t = times();
        double[] replayed = {2700, 5600, 8000}; // the video's own flashes, not ours
        double[] r = radius(t, 42, 0.26, 280, 300, replayed, 0.25, 3);
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, FLASHES);
        assertFalse(a.reflexPresent);
        assertTrue(a.result.abstained());
    }

    @Test
    void aReflexThatAnswersOnlyOneOfThreeFlashesDoesNotCount() {
        double[] t = times();
        double[] r = radius(t, 42, 0.26, 280, 300, new double[] {FLASHES[0]}, 0.25, 4);
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, FLASHES);
        assertFalse(a.reflexPresent);
    }

    @Test
    void aSluggishOldEyeStillPassesWithinTheLatencyWindow() {
        double[] t = times();
        // 520 ms leaves room for the fit's own latency/time-constant ambiguity (about +-50 ms) under the 700 ms bound;
        // an eye genuinely at 650+ ms can fall outside, and then the cue abstains rather than penalising it.
        double[] r = radius(t, 36, 0.12, 520, 400, FLASHES, 0.2, 5);
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, FLASHES);
        assertTrue(a.reflexPresent, "a 12% reflex at 520 ms is within the criteria");
    }

    @Test
    void aWeakReflexBelowSixPercentAbstains() {
        double[] t = times();
        double[] r = radius(t, 42, 0.03, 280, 300, FLASHES, 0.15, 6);
        assertTrue(new PupilLightReflexCue().evaluate(t, r, FLASHES).abstained());
    }

    @Test
    void aBlinkDuringAFlashDiscardsThatTrialButTheRestStillCount() {
        double[] t = times();
        double[] r = radius(t, 42, 0.26, 280, 300, FLASHES, 0.25, 7);
        for (int i = 0; i < t.length; i++) {
            if (t[i] > FLASHES[1] - 100 && t[i] < FLASHES[1] + 900) {
                r[i] = Double.NaN;
            }
        }
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, FLASHES);
        assertFalse(a.trials.get(1).valid);
        assertTrue(a.reflexPresent, "two valid, passing trials remain");
    }

    @Test
    void tooFewValidTrialsAbstains() {
        double[] t = times();
        double[] r = radius(t, 42, 0.26, 280, 300, FLASHES, 0.25, 8);
        PupilLightReflexCue.Analysis a = new PupilLightReflexCue().analyse(t, r, new double[] {FLASHES[0]});
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL, a.result.abstainReason());
    }

    @Test
    void noIrisCameraMeansAbstain() {
        CueResult r = new PupilLightReflexCue().evaluate(null, null, FLASHES);
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, r.abstainReason());
    }
}
