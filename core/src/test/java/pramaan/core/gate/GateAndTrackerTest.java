/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;

/** Build Map #19, #20: debounced single-face rule and track continuity. */
class GateAndTrackerTest {

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    private static FaceBox face(float x, float iod) {
        return new FaceBox(x, 100, iod * 2.5f, iod * 3, 0.95f,
                new float[] {x + iod * 0.75f, 150, x + iod * 1.75f, 150, x + iod * 1.25f, 190, x + iod * 0.8f, 230, x + iod * 1.7f, 230});
    }

    private static Instant at(long ms) {
        return T0.plusMillis(ms);
    }

    @Test
    void aPasserByFor200msIsIgnoredButAStayingSecondPersonIsReported() {
        FaceGate gate = new FaceGate();
        FaceBox resident = face(100, 80);
        FaceBox other = face(400, 70);
        assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(resident), at(0)).status());
        assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(resident, other), at(100)).status());
        assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(resident, other), at(300)).status());
        assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(resident), at(400)).status());

        assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(resident, other), at(1000)).status());
        assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(resident, other), at(1400)).status());
        assertEquals(FaceGate.Status.MULTIPLE_FACES, gate.update(List.of(resident, other), at(1800)).status());
    }

    @Test
    void anOperatorSmallInTheBackgroundDoesNotCount() {
        FaceGate gate = new FaceGate();
        for (long t = 0; t <= 2000; t += 100) {
            assertEquals(FaceGate.Status.ONE_FACE, gate.update(List.of(face(100, 80), face(500, 25)), at(t)).status());
        }
    }

    @Test
    void aFaceSwapAfterA500msGapStartsANewTrackAndReportsTheLoss() {
        FaceTracker tracker = new FaceTracker();
        long first = tracker.update(face(100, 80), at(0)).trackId();
        assertEquals(first, tracker.update(face(104, 80), at(100)).trackId(), "small motion keeps the track");
        assertEquals(first, tracker.update(null, at(250)).trackId(), "a 150 ms gap is not a loss");

        FaceTracker.Update afterGap = tracker.update(face(300, 90), at(700));
        assertNotEquals(first, afterGap.trackId());
        assertTrue(afterGap.previousTrackLost(), "evidence must be reset");
    }

    @Test
    void aJumpToANonOverlappingFaceIsANewTrackEvenWithoutAGap() {
        FaceTracker tracker = new FaceTracker();
        long first = tracker.update(face(100, 80), at(0)).trackId();
        FaceTracker.Update jump = tracker.update(face(600, 80), at(33));
        assertNotEquals(first, jump.trackId());
        assertTrue(jump.previousTrackLost());
        assertFalse(tracker.update(face(602, 80), at(66)).previousTrackLost());
    }
}
