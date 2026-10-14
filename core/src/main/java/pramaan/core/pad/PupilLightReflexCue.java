/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.ArrayList;
import java.util.List;

/**
 * Pupil light reflex (Bible Part 32; Build Map #90). After a visible flash the pupil constricts:
 *
 * <pre>r(t) = r0                                        t &lt; ts + tLat
 * r(t) = r0 (1 - A (1 - exp(-(t - ts - tLat) / tau)))   t &ge; ts + tLat</pre>
 *
 * <p>For each flash the cue fits {@code (tLat, A, tau)} to the measured radius by least squares — a grid over
 * latency and time constant, with the amplitude solved in closed form for each pair — and a trial <em>passes</em>
 * when {@code A >= 0.06}, {@code tLat} lies in 150-700 ms and {@code R^2 >= 0.7}. The reflex is present only when
 * every valid trial passes, so a response that is not time-locked to <em>each</em> randomly timed flash does not count.
 *
 * <p><strong>Positive evidence only</strong> (Part 32, "who the reflex fails for"; Part 9). A present, time-locked
 * reflex is a strong vote for life; an absent one — age, medication, eye surgery, some blindness, a bright room — is
 * <em>never</em> evidence against a person, so it abstains. Whether a printed iris, a screen or a replay "fails" is
 * reported in {@link Analysis#reflexPresent}, not turned into a negative vote. A trial with too many missing radii
 * (a blink) is discarded; fewer than {@value #MIN_TRIALS} valid trials abstains.
 */
public final class PupilLightReflexCue {

    public static final String NAME = "plr";
    public static final double MIN_AMPLITUDE = 0.06;
    public static final double MIN_LATENCY_MS = 150;
    public static final double MAX_LATENCY_MS = 700;
    public static final double MIN_R2 = 0.7;
    public static final int MIN_TRIALS = 2;
    /** Vote when a time-locked reflex is present. PROVISIONAL until the iris corpus exists. */
    public static final double PRESENT_P_LIVE = 0.97;
    static final double BASELINE_MS = 300;
    static final double WINDOW_MS = 1500;
    private static final double MAX_MISSING = 0.2;
    private static final double MAX_AMPLITUDE = 0.6;

    /** One flash's fit. */
    public static final class Trial {
        public final double flashMs;
        public final boolean valid;
        public final double latencyMs;
        public final double amplitude;
        public final double tauMs;
        public final double r2;

        Trial(double flashMs, boolean valid, double latencyMs, double amplitude, double tauMs, double r2) {
            this.flashMs = flashMs;
            this.valid = valid;
            this.latencyMs = latencyMs;
            this.amplitude = amplitude;
            this.tauMs = tauMs;
            this.r2 = r2;
        }

        /** Meets amplitude, latency and fit criteria. */
        public boolean passes() {
            return valid && amplitude >= MIN_AMPLITUDE && latencyMs >= MIN_LATENCY_MS
                    && latencyMs <= MAX_LATENCY_MS && r2 >= MIN_R2;
        }
    }

    /** The result with every fitted trial, for diagnostics and the evidence record. */
    public static final class Analysis {
        public final CueResult result;
        public final List<Trial> trials;
        public final boolean reflexPresent;

        Analysis(CueResult result, List<Trial> trials, boolean reflexPresent) {
            this.result = result;
            this.trials = trials;
            this.reflexPresent = reflexPresent;
        }
    }

    /**
     * @param timesMs frame times, strictly increasing
     * @param radiusPx pupil radius per frame; NaN where the pupil could not be segmented (a blink)
     * @param flashTimesMs the randomly scheduled flash instants, same clock as {@code timesMs}
     */
    public CueResult evaluate(double[] timesMs, double[] radiusPx, double[] flashTimesMs) {
        return analyse(timesMs, radiusPx, flashTimesMs).result;
    }

    public Analysis analyse(double[] timesMs, double[] radiusPx, double[] flashTimesMs) {
        if (timesMs == null || radiusPx == null || flashTimesMs == null || flashTimesMs.length == 0) {
            return abstained(CueResult.AbstainReason.CAPABILITY_MISSING, new ArrayList<>());
        }
        if (timesMs.length != radiusPx.length) {
            throw new IllegalArgumentException("one radius per frame time");
        }
        List<Trial> trials = new ArrayList<>();
        for (double ts : flashTimesMs) {
            trials.add(fit(timesMs, radiusPx, ts));
        }
        int valid = 0;
        boolean allPass = true;
        for (Trial t : trials) {
            if (t.valid) {
                valid++;
                allPass &= t.passes();
            }
        }
        if (valid < MIN_TRIALS) {
            return abstained(CueResult.AbstainReason.INPUT_TOO_SMALL, trials);
        }
        if (!allPass) {
            return abstained(CueResult.AbstainReason.NOT_APPLICABLE, trials); // absence is never evidence against
        }
        double meanAmplitude = 0;
        for (Trial t : trials) {
            if (t.valid) {
                meanAmplitude += t.amplitude / valid;
            }
        }
        return new Analysis(CueResult.vote(NAME, PRESENT_P_LIVE, meanAmplitude), trials, true);
    }

    private static Analysis abstained(CueResult.AbstainReason r, List<Trial> trials) {
        return new Analysis(CueResult.abstain(NAME, r), trials, false);
    }

    private static Trial fit(double[] t, double[] r, double ts) {
        List<double[]> base = new ArrayList<>();
        List<double[]> win = new ArrayList<>();
        int missing = 0;
        int inWindow = 0;
        for (int i = 0; i < t.length; i++) {
            double rel = t[i] - ts;
            if (rel < -BASELINE_MS || rel > WINDOW_MS) {
                continue;
            }
            inWindow++;
            if (Double.isNaN(r[i])) {
                missing++;
                continue;
            }
            double[] p = {rel, r[i]};
            if (rel < 0) {
                base.add(p);
            }
            win.add(p);
        }
        if (inWindow < 8 || base.size() < 2 || missing > MAX_MISSING * inWindow) {
            return new Trial(ts, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double r0 = median(base);
        if (r0 <= 0) {
            return new Trial(ts, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double mean = 0;
        for (double[] p : win) {
            mean += p[1] / win.size();
        }
        double total = 0;
        for (double[] p : win) {
            total += (p[1] - mean) * (p[1] - mean);
        }
        double bestSse = Double.MAX_VALUE;
        double bestLat = 0;
        double bestA = 0;
        double bestTau = 0;
        for (double lat = 100; lat <= 800; lat += 10) {
            for (double tau = 100; tau <= 800; tau += 50) {
                double num = 0;
                double den = 0;
                for (double[] p : win) {
                    double g = p[0] < lat ? 0 : 1 - Math.exp(-(p[0] - lat) / tau);
                    num += g * (r0 - p[1]);
                    den += g * g;
                }
                if (den <= 0) {
                    continue;
                }
                double a = Math.max(0, Math.min(MAX_AMPLITUDE, num / (r0 * den)));
                double sse = 0;
                for (double[] p : win) {
                    double g = p[0] < lat ? 0 : 1 - Math.exp(-(p[0] - lat) / tau);
                    double e = p[1] - r0 * (1 - a * g);
                    sse += e * e;
                }
                if (sse < bestSse) {
                    bestSse = sse;
                    bestLat = lat;
                    bestA = a;
                    bestTau = tau;
                }
            }
        }
        double r2 = total <= 1e-12 ? 0 : 1 - bestSse / total;
        return new Trial(ts, true, bestLat, bestA, bestTau, r2);
    }

    private static double median(List<double[]> samples) {
        double[] v = new double[samples.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = samples.get(i)[1];
        }
        java.util.Arrays.sort(v);
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }
}
