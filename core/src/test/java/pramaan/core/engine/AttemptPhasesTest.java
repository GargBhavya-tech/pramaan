/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.gate.FaceGate;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FlashLight;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;
import pramaan.core.source.PupilSource;
import pramaan.core.source.SessionCapabilities;

/** Build Map #85, #90: the two phases that run after the action steps, on their own, with no engine around them. */
class AttemptPhasesTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final DeviceMetadata DEVICE = new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1");
    private static final double[] AMBIENT = {120, 90, 80};

    private static Frame frameAt(long ms) {
        return new Frame(new byte[] {1}, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, T0.plusMillis(ms), ms, DEVICE);
    }

    private static FrameObservation face(double[] skin, double[] nose, double[] cheeks) {
        return FrameObservation.builder(FaceGate.Status.ONE_FACE, 1).regions(skin, nose, cheeks).build();
    }

    private static FlashSequence flashSequence(int fps) {
        SessionCapabilities caps = SessionCapabilities.of(fps, new DeviceCapabilities(DEVICE,
                EnumSet.of(Capability.RESIDENT_DISPLAY)));
        byte[] seed = new byte[32];
        seed[0] = 7;
        ChallengePlan plan = ChallengeGenerator.replay(seed).plan(List.of(ChallengeKind.FLASH), 0, 0, caps,
                AccessibilityAdjustments.NONE, EnumSet.noneOf(ChallengeKind.class));
        return plan.flash().orElseThrow();
    }

    private static final class Light implements FlashLight {
        FlashColour current;
        final List<FlashColour> seen = new ArrayList<>();
        int offs;

        @Override
        public void show(FlashColour colour) {
            current = colour;
            seen.add(colour);
        }

        @Override
        public void off() {
            current = null;
            offs++;
        }
    }

    // --- flash -----------------------------------------------------------------------------------------------

    /** Drives a flash phase with a face that answers the light one frame late with the given gain. */
    private static FlashPhase run(Light light, FlashSequence seq, double gain, long frameMs) {
        FlashPhase phase = new FlashPhase(light, seq);
        Random noise = new Random(1);
        FlashColour lagged = null;
        for (long t = 0; t < 20_000; t += frameMs) {
            double[] e = lagged == null ? new double[3] : new double[] {lagged.r(), lagged.g(), lagged.b()};
            double[] skin = new double[3];
            double[] nose = new double[3];
            double[] cheeks = new double[3];
            for (int k = 0; k < 3; k++) {
                skin[k] = AMBIENT[k] + gain * e[k] * (AMBIENT[k] / 100) + noise.nextGaussian() * 0.3;
                nose[k] = AMBIENT[k] + gain * 1.5 * e[k] * (AMBIENT[k] / 100) + noise.nextGaussian() * 0.3;
                cheeks[k] = AMBIENT[k] + gain * e[k] * (AMBIENT[k] / 100) + noise.nextGaussian() * 0.3;
            }
            boolean more = phase.advance(frameAt(t), face(skin, nose, cheeks));
            lagged = light.current; // what the light shows when the next frame is taken
            if (!more) {
                return phase;
            }
        }
        throw new AssertionError("the flash phase never finished");
    }

    @Test
    void theFlashPhasePlaysTheWholeSequenceAndLeavesTheLightOff() {
        FlashSequence seq = flashSequence(10);
        Light light = new Light();
        FlashPhase phase = run(light, seq, 25, 100);
        assertNull(light.current);
        assertTrue(light.seen.size() >= 3, "frames in which a colour was on screen: " + light.seen.size());
        assertTrue(seq.colours().containsAll(new java.util.HashSet<>(light.seen)), "only the plan's colours were shown");
        assertEquals(1, phase.results().size());
        CueResult r = phase.results().get(0);
        assertFalse(r.abstained());
        assertTrue(r.pLive() > 0.8, "p " + r.pLive());
    }

    @Test
    void aFaceThatIgnoresTheLightGetsAVoteAgainst() {
        FlashPhase phase = run(new Light(), flashSequence(10), 0, 100);
        CueResult r = phase.results().get(0);
        assertFalse(r.abstained());
        assertTrue(r.pLive() < 0.5, "p " + r.pLive());
    }

    @Test
    void theFlashPhaseWorksAtThreeFramesPerSecond() {
        // Steps are lengthened at low fps (700-1000 ms, 5 colours); a frame every 333 ms still gives it enough samples.
        FlashPhase phase = run(new Light(), flashSequence(3), 25, 333);
        CueResult r = phase.results().get(0);
        assertFalse(r.abstained(), String.valueOf(r.abstainReason()));
    }

    @Test
    void abortingTurnsTheLightOffWhateverItWasDoing() {
        Light light = new Light();
        FlashPhase phase = new FlashPhase(light, flashSequence(10));
        phase.advance(frameAt(0), face(AMBIENT, AMBIENT, AMBIENT));
        phase.advance(frameAt(700), face(AMBIENT, AMBIENT, AMBIENT));
        assertTrue(light.current != null, "a colour is on");
        phase.abort();
        assertNull(light.current);
    }

    @Test
    void theBudgetCoversTheLeadInEveryColourTheTailAndASecondOfSlack() {
        FlashSequence seq = flashSequence(10);
        long colours = 0;
        for (int d : seq.durationsMs()) {
            colours += d;
        }
        assertEquals(500 + colours + 600 + 1000, FlashPhase.budgetMs(seq));
    }

    // --- pupil -----------------------------------------------------------------------------------------------

    private static final class Iris implements PupilSource {
        final List<Long> stimuli = new ArrayList<>();
        long now;
        double amplitude;
        boolean noData;

        @Override
        public void stimulate() {
            stimuli.add(now);
        }

        @Override
        public double[][] radii(long from, long to) {
            if (noData) {
                return new double[0][];
            }
            List<double[]> rows = new ArrayList<>();
            Random noise = new Random(5);
            for (long t = from; t <= to; t += 33) {
                double drop = 0;
                for (long s : stimuli) {
                    double rel = t - s - 280;
                    if (rel >= 0) {
                        drop = Math.max(drop, amplitude * (1 - Math.exp(-rel / 300.0))
                                * (rel > 1000 ? Math.exp(-(rel - 1000) / 500.0) : 1));
                    }
                }
                rows.add(new double[] {t, 42 * (1 - drop) + noise.nextGaussian() * 0.25});
            }
            return rows.toArray(new double[0][]);
        }
    }

    private static PupilPhase drive(Iris iris) {
        PupilPhase phase = new PupilPhase(iris, new SecureRandom());
        for (long t = 0; t < 30_000; t += 33) {
            iris.now = T0.plusMillis(t).toEpochMilli();
            if (!phase.advance(frameAt(t), FrameObservation.undecodable())) {
                return phase;
            }
        }
        throw new AssertionError("the pupil phase never finished");
    }

    @Test
    void threeRandomFlashesWithGapsOfTwoToThreeAndAHalfSeconds() {
        for (int i = 0; i < 8; i++) {
            Iris iris = new Iris();
            iris.amplitude = 0.26;
            PupilPhase phase = drive(iris);
            assertEquals(PupilPhase.FLASHES, iris.stimuli.size());
            for (int g = 1; g < iris.stimuli.size(); g++) {
                long gap = iris.stimuli.get(g) - iris.stimuli.get(g - 1);
                // Flashes are scheduled at nominal offsets and fire on the first frame at or after each one, so the
                // observed gap is the nominal gap plus or minus up to one frame (33 ms here) at either end.
                assertTrue(gap >= PupilPhase.GAP_MIN_MS - 33 && gap < PupilPhase.GAP_MIN_MS + PupilPhase.GAP_SPAN_MS + 66,
                        "gap " + gap);
            }
            assertFalse(phase.results().get(0).abstained());
        }
    }

    @Test
    void aLiveReflexVotesForLifeAndAnAbsentOneOnlyAbstains() {
        Iris live = new Iris();
        live.amplitude = 0.26;
        assertTrue(drive(live).results().get(0).pLive() > 0.9);

        Iris printed = new Iris();
        printed.amplitude = 0;
        assertTrue(drive(printed).results().get(0).abstained(), "no reflex never counts against anyone");
    }

    @Test
    void anIrisCameraWithNoDataAbstainsForWantOfACapability() {
        Iris dead = new Iris();
        dead.noData = true;
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, drive(dead).results().get(0).abstainReason());
    }

    @Test
    void theWorstCaseBudgetFitsInsideWhatTheSessionWillGrant() {
        assertTrue(PupilPhase.WORST_CASE_MS <= pramaan.core.session.LivenessSession.MAX_PHASE_TIME.toMillis(),
                "worst case " + PupilPhase.WORST_CASE_MS + " ms");
    }
}
