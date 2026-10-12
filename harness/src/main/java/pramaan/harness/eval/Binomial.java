/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

/**
 * Exact binomial confidence intervals (Bible Part 21.4; Build Map #68). Clopper–Pearson by its definition:
 * the lower bound solves P(X >= k; n, p) = alpha/2 and the upper bound P(X <= k; n, p) = alpha/2, by bisection
 * on tail sums computed in log space. Checked against values computed independently with exact rational
 * arithmetic.
 */
public final class Binomial {

    private Binomial() {
    }

    /** A proportion with its interval. */
    public static final class Rate {
        private final long k;
        private final long n;
        private final double lower;
        private final double upper;

        Rate(long k, long n, double lower, double upper) {
            this.k = k;
            this.n = n;
            this.lower = lower;
            this.upper = upper;
        }

        public long errors() {
            return k;
        }

        public long n() {
            return n;
        }

        /** k / n; NaN when n is 0. */
        public double value() {
            return n == 0 ? Double.NaN : (double) k / n;
        }

        public double lower() {
            return lower;
        }

        public double upper() {
            return upper;
        }

        @Override
        public String toString() {
            if (n == 0) {
                return "n/a (N = 0)";
            }
            return String.format("%.2f%% [%.2f%%, %.2f%%] (%d/%d)", 100 * value(), 100 * lower, 100 * upper, k, n);
        }
    }

    /** Two-sided Clopper–Pearson interval at the given confidence (e.g. 0.95). */
    public static Rate clopperPearson(long k, long n, double confidence) {
        if (n == 0) {
            return new Rate(0, 0, 0, 1);
        }
        if (k < 0 || k > n) {
            throw new IllegalArgumentException("need 0 <= k <= n, got " + k + "/" + n);
        }
        double tail = (1 - confidence) / 2;
        double lower = k == 0 ? 0 : solve(p -> upperTail(k, n, p), tail);
        double upper = k == n ? 1 : solve(p -> 1 - lowerTail(k, n, p), 1 - tail);
        return new Rate(k, n, lower, upper);
    }

    /** One-sided upper bound: the p at which seeing k or fewer errors has probability 1 - confidence. */
    public static double upperOneSided(long k, long n, double confidence) {
        if (n == 0 || k >= n) {
            return 1;
        }
        return solve(p -> 1 - lowerTail(k, n, p), confidence);
    }

    interface Fn {
        double at(double p);
    }

    /** Bisection for an increasing function of p on [0, 1]. */
    private static double solve(Fn f, double target) {
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            if (f.at(mid) < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** P(X <= k). */
    static double lowerTail(long k, long n, double p) {
        if (p <= 0) {
            return 1;
        }
        if (p >= 1) {
            return k >= n ? 1 : 0;
        }
        return Math.min(1, sumPmf(0, k, n, p));
    }

    /** P(X >= k). */
    static double upperTail(long k, long n, double p) {
        if (k <= 0) {
            return 1;
        }
        if (p <= 0) {
            return 0;
        }
        if (p >= 1) {
            return 1;
        }
        return Math.min(1, sumPmf(k, n, n, p));
    }

    private static double sumPmf(long from, long to, long n, double p) {
        double logP = Math.log(p);
        double logQ = Math.log1p(-p);
        double logC = 0;
        double max = Double.NEGATIVE_INFINITY;
        double[] terms = new double[(int) (to - from + 1)];
        for (long i = 0; i <= to; i++) {
            if (i > 0) {
                logC += Math.log((double) (n - i + 1) / i);
            }
            if (i >= from) {
                double t = logC + i * logP + (n - i) * logQ;
                terms[(int) (i - from)] = t;
                max = Math.max(max, t);
            }
        }
        double s = 0;
        for (double t : terms) {
            s += Math.exp(t - max);
        }
        return Math.exp(max) * s;
    }
}
