/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.passive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Build Map #25, #26: trimmed logit mean resists outliers; Platt fitting produces honest probabilities. */
class LogitsAndCalibrationTest {

    @Test
    void oneOverconfidentFrameMovesTheTrimmedLogitMeanLessThanAProbabilityMean() {
        double[] probs = {0.6, 0.62, 0.58, 0.61, 0.59, 0.6, 0.63, 0.57, 0.6, 0.999999};
        double[] logits = new double[probs.length];
        for (int i = 0; i < probs.length; i++) {
            logits[i] = Logits.logit(probs[i]);
        }
        double trimmed = Logits.sigmoid(Logits.trimmedMean(logits, 0.1));
        double plain = java.util.Arrays.stream(probs).average().orElseThrow();
        assertTrue(Math.abs(trimmed - 0.6) < Math.abs(plain - 0.6), "trimmed=" + trimmed + " plain=" + plain);
        assertEquals(0.6, trimmed, 0.01);
    }

    @Test
    void plattFittingIsCalibratedOnSyntheticData() {
        // Raw scores that are over-confident by a factor of 3: true p = sigmoid(x / 3 + 0.5).
        Random rng = new Random(11);
        int n = 20_000;
        double[] raw = new double[n];
        boolean[] live = new boolean[n];
        for (int i = 0; i < n; i++) {
            raw[i] = rng.nextGaussian() * 6;
            live[i] = rng.nextDouble() < Logits.sigmoid(raw[i] / 3 + 0.5);
        }
        PlattCalibration cal = PlattCalibration.fit(raw, live, "sha", "webcam");
        assertEquals(1.0 / 3, cal.a(), 0.02);
        assertEquals(0.5, cal.b(), 0.05);

        // Reliability: in ten probability buckets, predicted and observed frequencies agree.
        double[] predSum = new double[10];
        double[] hits = new double[10];
        int[] count = new int[10];
        for (int i = 0; i < n; i++) {
            double p = cal.probability(raw[i]);
            int b = Math.min(9, (int) (p * 10));
            predSum[b] += p;
            hits[b] += live[i] ? 1 : 0;
            count[b]++;
        }
        for (int b = 0; b < 10; b++) {
            if (count[b] > 300) {
                assertEquals(predSum[b] / count[b], hits[b] / count[b], 0.04, "bucket " + b);
            }
        }
    }

    @Test
    void aCalibrationForAnotherModelIsRefused() {
        byte[] json = "{\"model\":\"aaa\",\"a\":0.5,\"b\":0.1,\"provisional\":false}".getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> PlattCalibration.fromJson(json, "bbb"));
        assertTrue(PlattCalibration.IDENTITY.provisional());
    }
}
