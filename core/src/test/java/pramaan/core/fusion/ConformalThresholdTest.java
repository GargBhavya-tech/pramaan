/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Bible Part 9, 24: a cut-off whose false-rejection rate is guaranteed, not hoped for. */
class ConformalThresholdTest {

    @Test
    void theSmallestNumberOfSamplesThatCanSupportATargetIsOneOverAlphaMinusOne() {
        assertEquals(19, ConformalThreshold.minimumSamples(0.05));
        assertEquals(99, ConformalThreshold.minimumSamples(0.01));
        assertEquals(9, ConformalThreshold.minimumSamples(0.1));
    }

    @Test
    void tooFewSamplesMeansNoScoreRuleCanBeGuaranteedSoNobodyIsRejectedOnScore() {
        ConformalThreshold t = ConformalThreshold.calibrate(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0.05);
        assertFalse(t.canReject());
        assertTrue(t.accepts(-1e9), "even a terrible score is not rejected on a guarantee that does not exist");
        assertEquals(0.0, t.guaranteedBpcer(), 0.0);
        assertTrue(t.statement().contains("cannot be guaranteed"), t.statement());
    }

    @Test
    void theCutOffIsTheKthSmallestGenuineScore() {
        double[] genuine = new double[199];
        for (int i = 0; i < 199; i++) {
            genuine[i] = i; // 0..198
        }
        ConformalThreshold t = ConformalThreshold.calibrate(genuine, 0.05);
        // k = floor(0.05 * 200) = 10, so the 10th smallest: 9
        assertEquals(9.0, t.threshold(), 0.0);
        assertEquals(10.0 / 200, t.guaranteedBpcer(), 1e-12);
        assertFalse(t.accepts(9.0), "at the cut-off is rejected");
        assertTrue(t.accepts(9.5));
    }

    /** The whole point: over many fresh calibration sets, a fresh genuine resident is rejected at most alpha of the time. */
    @Test
    void theFalseRejectionRateOfFreshGenuineResidentsIsAtMostAlphaWhateverTheDistribution() {
        Random r = new Random(11);
        double alpha = 0.05;
        for (int dist = 0; dist < 3; dist++) {
            long rejected = 0;
            long total = 0;
            for (int rep = 0; rep < 300; rep++) {
                double[] cal = new double[199];
                for (int i = 0; i < cal.length; i++) {
                    cal[i] = draw(r, dist);
                }
                ConformalThreshold t = ConformalThreshold.calibrate(cal, alpha);
                for (int i = 0; i < 200; i++) {
                    rejected += t.accepts(draw(r, dist)) ? 0 : 1;
                    total++;
                }
            }
            double rate = (double) rejected / total;
            assertTrue(rate <= alpha + 0.006, "distribution " + dist + ": " + rate);
            assertTrue(rate > alpha - 0.012, "and not wastefully strict: " + rate);
        }
    }

    private static double draw(Random r, int dist) {
        switch (dist) {
            case 0:
                return r.nextGaussian();
            case 1: // heavy right tail
                return Math.exp(r.nextGaussian());
            default: // two bumps
                return r.nextBoolean() ? r.nextGaussian() : 5 + 0.3 * r.nextGaussian();
        }
    }

    @Test
    void eachGroupGetsItsOwnCutOffFromItsOwnGenuineScores() {
        Random r = new Random(3);
        double[] easy = new double[199];
        double[] hard = new double[199];
        for (int i = 0; i < 199; i++) {
            easy[i] = 3 + r.nextGaussian();
            hard[i] = 0 + r.nextGaussian(); // a group that scores lower, for innocent reasons
        }
        Map<String, ConformalThreshold> by = ConformalThreshold.byGroup(Map.of("easy", easy, "hard", hard), 0.05);
        assertTrue(by.get("easy").threshold() > by.get("hard").threshold() + 2,
                "a single shared cut-off would reject the hard group far more than 5%");
        assertTrue(by.get("hard").canReject());
    }

    @Test
    void alphaOutsideZeroOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ConformalThreshold.calibrate(new double[] {1}, 0));
        assertThrows(IllegalArgumentException.class, () -> ConformalThreshold.calibrate(new double[] {1}, 1));
        assertThrows(IllegalArgumentException.class, () -> ConformalThreshold.minimumSamples(-0.1));
    }
}
