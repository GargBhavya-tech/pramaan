/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.detect.FaceBox;
import pramaan.core.gate.FaceTracker;
import pramaan.core.pad.CueResult;

/** Build Map #52 (and #53's chain): evidence cannot span a discontinuity. */
class EvidenceResetTest {

    private SessionFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SessionFixtures();
    }

    // ---------------------------------------------------------------- chain
    @Test
    void droppingReorderingOrSubstitutingAFrameChangesTheHead() {
        SessionNonce nonce = SessionNonce.generate(new SecureRandom());
        byte[][] frames = {{1}, {2}, {3}};
        String clean = chain(nonce, frames, new long[] {10, 20, 30});
        assertEquals(clean, chain(nonce, frames, new long[] {10, 20, 30}), "the same session replayed gives the same head");
        assertNotEquals(clean, chain(nonce, new byte[][] {{1}, {3}}, new long[] {10, 30}), "dropped");
        assertNotEquals(clean, chain(nonce, new byte[][] {{2}, {1}, {3}}, new long[] {10, 20, 30}), "reordered");
        assertNotEquals(clean, chain(nonce, new byte[][] {{1}, {9}, {3}}, new long[] {10, 20, 30}), "substituted");
        assertNotEquals(clean, chain(nonce, frames, new long[] {10, 21, 30}), "retimed");
        assertNotEquals(clean, chain(SessionNonce.generate(new SecureRandom()), frames, new long[] {10, 20, 30}), "other nonce");
        assertThrows(IllegalArgumentException.class, () -> chain(nonce, frames, new long[] {10, 30, 20}));
    }

    private static String chain(SessionNonce nonce, byte[][] frames, long[] t) {
        FrameHashChain c = new FrameHashChain(nonce);
        for (int i = 0; i < frames.length; i++) {
            c.append(frames[i], t[i]);
        }
        return c.head();
    }

    // ---------------------------------------------------------------- reset
    @Test
    void theSwapClipCannotReachAcceptWithPreGapEvidence() {
        // Track 1 builds evidence and passes a challenge step; the face leaves for 500 ms; a different face returns.
        FaceTracker tracker = new FaceTracker();
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        SessionEventRouter router = new SessionEventRouter(s);
        s.start();
        s.faceAcquired();
        long track1 = tracker.update(new FaceBox(100, 100, 200, 200, 0.9f, new float[10]), fx.clock.instant()).trackId();
        for (int i = 0; i < 15; i++) {
            fx.clock.advanceMillis(100);
            tracker.update(new FaceBox(100, 100, 200, 200, 0.9f, new float[10]), fx.clock.instant());
            s.frameAnalysed(new byte[] {(byte) i}, fx.clock.millis(), track1);
            s.recordCue(CueResult.vote("passive", 0.9, 0));
        }
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(2));
        s.challengeStepPassed();
        String preGapHead = s.evidenceHead();
        assertEquals(16 - 1, s.evidenceFrames());

        fx.clock.advanceMillis(500);
        FaceTracker.Update swap = tracker.update(new FaceBox(400, 120, 500, 220, 0.9f, new float[10]), fx.clock.instant());
        assertTrue(swap.previousTrackLost());
        assertNotEquals(track1, swap.trackId());
        router.onTrack(swap);

        assertEquals(SessionState.ACQUIRING, s.state());
        assertEquals(0, s.evidenceFrames(), "no frame from before the gap survives");
        assertTrue(s.evidenceCues().isEmpty(), "no cue from before the gap survives");
        assertNotEquals(preGapHead, s.evidenceHead());
        assertFalse(s.challengeStepPassed(), "nor does challenge progress");

        // The new face must earn everything again, from its own frames.
        s.faceAcquired();
        fx.clock.advance(Duration.ofMillis(1500));
        assertFalse(s.passiveDecided(fx.accept(Workflow.RESIDENT), null), "no decision without post-gap frames");
        s.frameAnalysed(new byte[] {42}, fx.clock.millis(), swap.trackId());
        assertTrue(s.passiveDecided(fx.accept(Workflow.RESIDENT), null));
        assertEquals(swap.trackId(), s.evidenceTrackId());
        assertEquals(1, s.evidenceFrames());
    }

    @Test
    void aFrameFromAnotherTrackResetsEvenIfNobodyReportedTheLoss() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        s.start();
        s.faceAcquired();
        assertTrue(s.frameAnalysed(new byte[] {1}, fx.clock.millis(), 7));
        fx.clock.advanceMillis(33);
        assertFalse(s.frameAnalysed(new byte[] {2}, fx.clock.millis(), 8));
        assertEquals(SessionState.ACQUIRING, s.state());
        assertEquals(0, s.evidenceFrames());
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("EVIDENCE_TRACK_SWITCH")));
    }

    @Test
    void aFrameGapLongerThanTheTrackGapRestartsEvidence() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        SessionEventRouter router = new SessionEventRouter(s);
        s.start();
        s.faceAcquired();
        router.onFrame(frameAt(fx.clock.instant(), 0), new byte[] {1}, 1);
        fx.clock.advanceMillis(100);
        router.onFrame(frameAt(fx.clock.instant(), 1), new byte[] {2}, 1);
        assertEquals(2, s.evidenceFrames());
        fx.clock.advanceMillis(301);
        router.onFrame(frameAt(fx.clock.instant(), 2), new byte[] {3}, 1);
        assertEquals(SessionState.ACQUIRING, s.state(), "301 ms without a frame is a lost face");
        assertEquals(0, s.evidenceFrames());
    }

    @Test
    void theChallengeMustBeDecidedOnFramesFromTheChallenge() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(1));
        s.challengeStepPassed();
        assertFalse(s.challengesDecided(fx.acceptAfterChallenge(Workflow.RESIDENT)), "no frame seen during the challenge");
        fx.feed(s);
        assertTrue(s.challengesDecided(fx.acceptAfterChallenge(Workflow.RESIDENT)));
    }

    static pramaan.core.source.Frame frameAt(Instant t, long index) {
        return new pramaan.core.source.Frame(new byte[] {(byte) 0xFF, (byte) 0xD8}, pramaan.core.source.PixelFormat.JPEG, 0, 0,
                pramaan.core.source.Frame.NO_SENSOR_TIMESTAMP, t, index, null);
    }

    @Test
    void evidenceIsBoundToTheAttemptsNonce() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        s.start();
        FrameHashChain expected = new FrameHashChain(s.nonce());
        assertEquals(expected.head(), s.evidenceHead());
        List<CueResult> none = s.evidenceCues();
        assertTrue(none.isEmpty());
    }
}
