/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.passive;

import java.util.Arrays;

/** Probability/logit helpers and the trimmed-mean aggregation of Build Map #25. */
public final class Logits {

    private static final double EPS = 1e-6;

    private Logits() {
    }

    public static double logit(double p) {
        double q = Math.max(EPS, Math.min(1 - EPS, p));
        return Math.log(q / (1 - q));
    }

    public static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    /**
     * Trimmed mean of logits: drop the top and bottom {@code trim} fraction, average the rest. Averaging
     * logits (not probabilities) and trimming stops one over-confident or blurred frame dominating.
     */
    public static double trimmedMean(double[] logits, double trim) {
        if (logits.length == 0) {
            throw new IllegalArgumentException("no scores to aggregate");
        }
        if (trim < 0 || trim >= 0.5) {
            throw new IllegalArgumentException("trim must be in [0, 0.5)");
        }
        double[] sorted = logits.clone();
        Arrays.sort(sorted);
        int drop = (int) Math.floor(sorted.length * trim);
        double sum = 0;
        for (int i = drop; i < sorted.length - drop; i++) {
            sum += sorted[i];
        }
        return sum / (sorted.length - 2 * drop);
    }
}
