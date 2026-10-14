/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.FlashColour;

/** Bible Part 8.6 and Build Map #85: live, phone replay, injection, and the abstentions. */
class FlashReflectionCueTest {

    private static final FlashColour[] SEQUENCE = {FlashColour.WHITE, FlashColour.GREEN, FlashColour.BLUE,
        FlashColour.YELLOW, FlashColour.MAGENTA, FlashColour.CYAN, FlashColour.BLUE, FlashColour.WHITE};
    /** Frames each colour is held, plus a dark lead-in and tail. */
    private static final int HOLD = 3;
    private static final int LEAD = 4;
    private static final int TAIL = 4;
    private static final double[] AMBIENT = {120, 90, 80};

    private static FlashColour colourAt(int frame) {
        int k = (frame - LEAD) / HOLD;
        return frame < LEAD || k >= SEQUENCE.length ? null : SEQUENCE[k];
    }

    private static double[] lightAt(int frame, int lag, FlashColour[] source) {
        int f = frame - lag;
        int k = (f - LEAD) / HOLD;
        if (f < LEAD || k >= source.length) {
            return new double[] {0, 0, 0};
        }
        return new double[] {source[k].r(), source[k].g(), source[k].b()};
    }

    private static double[] response(double[] ambient, double[] light, double strength, Random noise, double sigma) {
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = ambient[c] + strength * light[c] * (ambient[c] / 100) + noise.nextGaussian() * sigma;
        }
        return out;
    }

    /**
     * @param responseSource the colours the face actually answers (the emitted ones for a live face)
     * @param lag camera lag in frames
     * @param noseGain nose strength as a multiple of the cheek strength
     */
    private static FlashTrace trace(FlashColour[] responseSource, int lag, double strength, double noseGain,
            double sigma, double[] ambient, long seed) {
        Random noise = new Random(seed);
        FlashTrace t = new FlashTrace();
        int frames = LEAD + SEQUENCE.length * HOLD + TAIL;
        for (int f = 0; f < frames; f++) {
            double[] light = lightAt(f, lag, responseSource);
            double[] face = response(ambient, light, strength, noise, sigma);
            double[] nose = response(ambient, light, strength * noseGain, noise, sigma);
            double[] cheek = response(ambient, light, strength, noise, sigma);
            t.add(Optional.ofNullable(colourAt(f)), face, nose, cheek);
        }
        return t;
    }

    @Test
    void aLiveFaceAnswersTheFlashAtItsCameraLag() {
        for (long seed = 1; seed <= 5; seed++) {
            FlashReflectionCue.Analysis a = new FlashReflectionCue().analyse(
                    trace(SEQUENCE, 1, 25, 1.4, 0.4, AMBIENT, seed));
            assertTrue(!a.result.abstained());
            assertEquals(1, a.lag, "seed " + seed);
            assertTrue(a.rho > 0.7, "rho " + a.rho + " for seed " + seed);
            assertTrue(a.result.pLive() > 0.9, "p " + a.result.pLive());
        }
    }

    @Test
    void aPhoneReplayCarriesItsOwnColoursNotOurs() {
        FlashColour[] other = {FlashColour.CYAN, FlashColour.YELLOW, FlashColour.WHITE, FlashColour.MAGENTA,
            FlashColour.GREEN, FlashColour.WHITE, FlashColour.CYAN, FlashColour.BLUE};
        for (long seed = 1; seed <= 5; seed++) {
            FlashReflectionCue.Analysis a = new FlashReflectionCue().analyse(
                    trace(other, 0, 25, 1.0, 0.4, AMBIENT, seed));
            assertTrue(a.rho < 0.5, "rho " + a.rho + " for seed " + seed);
            assertTrue(a.result.pLive() < 0.5, "p " + a.result.pLive());
        }
    }

    @Test
    void anInjectedStreamHasNoResponseAtAll() {
        for (long seed = 1; seed <= 5; seed++) {
            FlashReflectionCue.Analysis a = new FlashReflectionCue().analyse(
                    trace(SEQUENCE, 0, 0, 1.0, 0.4, AMBIENT, seed));
            assertTrue(!a.result.abstained(), "weak response in normal light is evidence, not an abstention");
            assertTrue(Math.abs(a.rho) < 0.45, "rho " + a.rho);
            assertTrue(a.result.pLive() < 0.5, "p " + a.result.pLive());
        }
    }

    @Test
    void aResponseThatArrivesTooLateDoesNotPass() {
        FlashReflectionCue.Analysis a = new FlashReflectionCue().analyse(
                trace(SEQUENCE, 3, 25, 1.4, 0.2, AMBIENT, 7));
        assertTrue(a.lag <= FlashReflectionCue.MAX_PASS_LAG);
        assertTrue(a.rho < FlashReflectionCue.PASS_RHO, "a 3-frame delay is outside the allowed lag: rho " + a.rho);
    }

    @Test
    void aFlatPrintShadesUniformlyAndScoresLowerThanARealNose() {
        FlashReflectionCue cue = new FlashReflectionCue();
        double flat = cue.analyse(trace(SEQUENCE, 1, 25, 1.0, 0.2, AMBIENT, 3)).result.pLive();
        double solid = cue.analyse(trace(SEQUENCE, 1, 25, 1.5, 0.2, AMBIENT, 3)).result.pLive();
        assertTrue(solid > flat, "3-D shading should raise the vote: " + solid + " vs " + flat);
    }

    @Test
    void theNoseToCheekRatioIsReported() {
        FlashReflectionCue.Analysis a = new FlashReflectionCue().analyse(
                trace(SEQUENCE, 1, 25, 1.5, 0.05, AMBIENT, 3));
        assertEquals(1.5, a.noseToCheekGain, 0.2);
    }

    @Test
    void noResidentFacingLightMeansAbstain() {
        CueResult r = new FlashReflectionCue().evaluate(null);
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, r.abstainReason());
    }

    @Test
    void sunlitAmbientAbstainsRatherThanFailingAnyone() {
        double[] glare = {230, 215, 205};
        CueResult r = new FlashReflectionCue().evaluate(trace(SEQUENCE, 1, 25, 1.4, 0.4, glare, 1));
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.NOT_APPLICABLE, r.abstainReason());
    }

    @Test
    void aLightThatNeverChangedAbstains() {
        FlashTrace t = new FlashTrace();
        for (int i = 0; i < 20; i++) {
            t.add(Optional.empty(), AMBIENT, AMBIENT, AMBIENT);
        }
        assertTrue(new FlashReflectionCue().evaluate(t).abstained());
    }

    @Test
    void aTooShortTraceAbstains() {
        FlashTrace t = new FlashTrace();
        for (int i = 0; i < 5; i++) {
            t.add(Optional.of(FlashColour.WHITE), AMBIENT, AMBIENT, AMBIENT);
        }
        CueResult r = new FlashReflectionCue().evaluate(t);
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL, r.abstainReason());
    }
}
