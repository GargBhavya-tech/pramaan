/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;
import pramaan.core.gate.FaceGate;
import pramaan.core.gate.FaceTracker;
import pramaan.core.pad.CueResult;
import pramaan.core.passive.Logits;

/** The engine loop's own rules, below the end-to-end runs in the harness. */
class EngineUnitsTest {

    private static final FaceBox BOX = new FaceBox(100, 100, 200, 200, 0.95f, new float[10]);

    @Test
    void theTrackGapStretchesToOneAndAHalfFrameIntervalsOnSlowStreams() {
        assertEquals(Duration.ofMillis(500), FaceTracker.gapFor(3));
        assertEquals(Duration.ofMillis(300), FaceTracker.gapFor(10), "fast streams keep the 300 ms gap");
        assertEquals(Duration.ofMillis(300), FaceTracker.gapFor(30));
        assertThrows(IllegalArgumentException.class, () -> FaceTracker.gapFor(0));
    }

    @Test
    void aThreeFpsStreamKeepsItsTrackButADroppedFrameStillEndsIt() {
        Instant t0 = Instant.parse("2026-09-23T10:00:00Z");
        FaceTracker fixed = new FaceTracker();
        FaceTracker scaled = new FaceTracker(FaceTracker.gapFor(3));
        fixed.update(BOX, t0);
        scaled.update(BOX, t0);
        assertTrue(fixed.update(BOX, t0.plusMillis(333)).previousTrackLost(), "the old fixed gap lost every 3 fps frame");
        assertFalse(scaled.update(BOX, t0.plusMillis(333)).previousTrackLost());
        assertTrue(scaled.update(BOX, t0.plusMillis(333 + 667)).previousTrackLost(), "one dropped frame restarts the evidence");
    }

    @Test
    void frameScoresBecomeOneTrimmedLogitMeanVotePerCue() {
        CueAccumulator acc = new CueAccumulator();
        double[] ps = {0.6, 0.62, 0.64, 0.61, 0.63, 0.6, 0.62, 0.64, 0.61, 0.999};
        for (double p : ps) {
            acc.addProbability("passive", p);
        }
        acc.add(CueResult.vote("moire", 0.7, 3));
        acc.add(CueResult.abstain("moire", CueResult.AbstainReason.INPUT_TOO_SMALL));
        acc.add(CueResult.abstain("colorTexture", CueResult.AbstainReason.UNTRAINED));
        List<CueResult> out = acc.results();
        assertEquals(3, out.size(), "one result per cue: fusion refuses a cue that votes twice");
        CueResult passive = out.get(0);
        double[] logits = new double[ps.length];
        for (int i = 0; i < ps.length; i++) {
            logits[i] = Logits.logit(ps[i]);
        }
        assertEquals(Logits.sigmoid(Logits.trimmedMean(logits, 0.1)), passive.pLive(), 1e-12);
        assertTrue(passive.pLive() < 0.65, "one over-confident frame is trimmed away: " + passive.pLive());
        assertFalse(out.get(1).abstained(), "a cue that voted on any frame votes");
        assertEquals(CueResult.AbstainReason.UNTRAINED, out.get(2).abstainReason());
        acc.clear();
        assertTrue(acc.results().isEmpty());
    }

    @Test
    void aProbabilityOfExactlyOneFromAModelDoesNotBreakTheVote() {
        CueAccumulator acc = new CueAccumulator();
        acc.addProbability("passive", 1.0);
        double p = acc.results().get(0).pLive();
        assertTrue(p > 0.99 && p < 1, String.valueOf(p));
    }

    @Test
    void onlyASingleFaceFrameCarriesScoresOrLandmarks() {
        assertThrows(IllegalStateException.class,
                () -> FrameObservation.builder(FaceGate.Status.MULTIPLE_FACES, 1).passive(0.9).build());
        assertThrows(IllegalStateException.class, () -> FrameObservation.builder(FaceGate.Status.NO_FACE, 0)
                .moire(CueResult.vote("moire", 0.5, 0)).build());
        FrameObservation bad = FrameObservation.undecodable();
        assertFalse(bad.decoded());
        assertTrue(Double.isNaN(bad.passive()));
    }
}
