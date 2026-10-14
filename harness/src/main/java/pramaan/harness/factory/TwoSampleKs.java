/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.util.Arrays;

/**
 * Two-sample Kolmogorov-Smirnov test (Bible Part 45, "Validating realism"): are a cue's statistics on synthetic
 * attacks drawn from the same distribution as on real attacks? {@code D} is the largest gap between the two empirical
 * CDFs; the p-value is the asymptotic Kolmogorov series with the usual finite-sample correction, which is adequate
 * from about 8 samples a side. A small p means the simulator has not matched reality on that cue.
 */
public final class TwoSampleKs {

    private TwoSampleKs() {
    }

    /** Largest absolute difference between the two empirical CDFs. */
    public static double statistic(double[] a, double[] b) {
        if (a.length == 0 || b.length == 0) {
            throw new IllegalArgumentException("both samples need at least one value");
        }
        double[] x = a.clone();
        double[] y = b.clone();
        Arrays.sort(x);
        Arrays.sort(y);
        int i = 0;
        int j = 0;
        double d = 0;
        while (i < x.length && j < y.length) {
            double v = Math.min(x[i], y[j]);
            while (i < x.length && x[i] <= v) {
                i++;
            }
            while (j < y.length && y[j] <= v) {
                j++;
            }
            d = Math.max(d, Math.abs((double) i / x.length - (double) j / y.length));
        }
        return d;
    }

    /** Asymptotic p-value of {@code D} for samples of the given sizes. */
    public static double pValue(double d, int n, int m) {
        double en = Math.sqrt((double) n * m / (n + m));
        double lambda = (en + 0.12 + 0.11 / en) * d;
        if (lambda < 0.2) {
            return 1;
        }
        double sum = 0;
        for (int k = 1; k <= 100; k++) {
            double term = 2 * Math.exp(-2.0 * k * k * lambda * lambda) * (k % 2 == 1 ? 1 : -1);
            sum += term;
            if (Math.abs(term) < 1e-12) {
                break;
            }
        }
        return Math.max(0, Math.min(1, sum));
    }

    public static double pValue(double[] a, double[] b) {
        return pValue(statistic(a, b), a.length, b.length);
    }
}
