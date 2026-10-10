/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Is this operator abnormal? (Bible Part 18.2; Build Map #88.) For each signal — PAD fails, binding fails,
 * accessibility-profile use, exceptions — an operator's count {@code x} in {@code n} attempts is compared with their
 * centre's rate {@code p0} by a binomial z-score, {@code z = (x - n p0) / sqrt(n p0 (1 - p0))}, and placed on a
 * funnel plot whose ±3 SD limits narrow as {@code n} grows, so a small operator is not flagged for noise and a big
 * one not for size. Flags are for review, never an automatic block: analytics alone blocks nothing (Part 18.3).
 */
public final class FraudAnalytics {

    /** Signals counted per attempt. */
    public enum Signal {
        /** A PAD reject or a hard veto: attacks tried, or equipment trouble. */
        PAD_FAIL,
        /** The capture could not be tied to the session: a swap tried, or a mis-set device. */
        BINDING_FAIL,
        /** An accessibility profile, which relaxes checks, was selected. */
        ACCESSIBILITY,
        /** An exception was approved for a resident this operator served. */
        EXCEPTION
    }

    /** Standard deviations for a flag and for the funnel's limits (Part 44: ±3 SD). */
    public static final double FLAG_Z = 3.0;

    /** One attempt, as the audit log records it: whose, where, and which signals it raised. */
    public static final class Attempt {
        final String operatorId;
        final String centreId;
        final Set<Signal> signals;

        public Attempt(String operatorId, String centreId, Set<Signal> signals) {
            this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
            this.centreId = Objects.requireNonNull(centreId, "centreId");
            this.signals = signals;
        }
    }

    /** One operator's line for one signal. */
    public static final class Line {
        public final String operatorId;
        public final String centreId;
        public final Signal signal;
        public final long count;
        public final long attempts;
        public final double centreRate;
        public final double z;
        public final double lowerLimit;
        public final double upperLimit;

        Line(String operatorId, String centreId, Signal signal, long count, long attempts, double centreRate) {
            this.operatorId = operatorId;
            this.centreId = centreId;
            this.signal = signal;
            this.count = count;
            this.attempts = attempts;
            this.centreRate = centreRate;
            this.z = zScore(count, attempts, centreRate);
            double[] l = funnelLimits(attempts, centreRate, FLAG_Z);
            this.lowerLimit = l[0];
            this.upperLimit = l[1];
        }

        public double rate() {
            return attempts == 0 ? 0 : (double) count / attempts;
        }

        /** Above the funnel's upper limit: more of this signal than chance explains. */
        public boolean flagged() {
            return z >= FLAG_Z && rate() > upperLimit;
        }
    }

    private FraudAnalytics() {
    }

    /** {@code (x - n p0) / sqrt(n p0 (1 - p0))}; 0 when the baseline is 0 or 1 (no variation to measure against). */
    public static double zScore(long x, long n, double p0) {
        double var = n * p0 * (1 - p0);
        return var <= 0 ? 0 : (x - n * p0) / Math.sqrt(var);
    }

    /** Funnel-plot control limits on a rate: {@code p ± k sqrt(p (1 - p) / n)}, clamped to [0, 1]. */
    public static double[] funnelLimits(long n, double p, double k) {
        if (n <= 0) {
            return new double[] {0, 1};
        }
        double sd = Math.sqrt(p * (1 - p) / n);
        return new double[] {Math.max(0, p - k * sd), Math.min(1, p + k * sd)};
    }

    /** Every operator × signal line, each against its own centre's rate for that signal. */
    public static List<Line> analyse(List<Attempt> attempts) {
        Map<String, long[]> centre = new TreeMap<>();
        Map<String, long[]> operator = new TreeMap<>();
        Map<String, String> centreOf = new TreeMap<>();
        for (Attempt a : attempts) {
            long[] c = centre.computeIfAbsent(a.centreId, k -> new long[Signal.values().length + 1]);
            String key = a.operatorId + "\u0000" + a.centreId;
            long[] o = operator.computeIfAbsent(key, k -> new long[Signal.values().length + 1]);
            centreOf.put(key, a.centreId);
            c[0]++;
            o[0]++;
            for (Signal s : a.signals) {
                c[s.ordinal() + 1]++;
                o[s.ordinal() + 1]++;
            }
        }
        List<Line> out = new ArrayList<>();
        operator.forEach((key, o) -> {
            String op = key.substring(0, key.indexOf('\u0000'));
            long[] c = centre.get(centreOf.get(key));
            for (Signal s : Signal.values()) {
                double p0 = (double) c[s.ordinal() + 1] / c[0];
                out.add(new Line(op, centreOf.get(key), s, o[s.ordinal() + 1], o[0], p0));
            }
        });
        return Collections.unmodifiableList(out);
    }
}
