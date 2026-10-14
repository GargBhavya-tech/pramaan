/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A threshold with a guarantee instead of a guess (Bible Part 9, 24): split conformal calibration on genuine residents.
 *
 * <p>Given {@code n} genuine fused scores from a calibration set and a target {@code alpha}, reject a new session whose
 * score is at or below the {@code k}-th smallest calibration score, where {@code k = floor(alpha * (n + 1))}. If the new
 * genuine resident's score is exchangeable with the calibration scores (drawn from the same station and population, in
 * no particular order), the chance they are rejected is exactly {@code k / (n + 1) <= alpha}. That holds for any
 * score distribution and any {@code n}: no tail model, no asymptotics.
 *
 * <p>What it does <em>not</em> promise: anything about attacks (APCER is measured separately, with its own interval),
 * anything if the station or population drifts from the calibration set, or anything with fewer than
 * {@link #minimumSamples(double)} genuine scores, where {@code k = 0} and the only guaranteed-safe rule is to reject
 * nobody on score alone.
 *
 * <p>Calibrate per device class and per group (a Mondrian partition) so the guarantee holds inside each, not just on
 * average: {@link #byGroup}.
 */
public final class ConformalThreshold {

    private final double alpha;
    private final int n;
    private final int k;
    private final double tau;

    private ConformalThreshold(double alpha, int n, int k, double tau) {
        this.alpha = alpha;
        this.n = n;
        this.k = k;
        this.tau = tau;
    }

    /** The fewest genuine scores for which a rejection rule can be guaranteed at {@code alpha}: {@code ceil(1/alpha) - 1}. */
    public static int minimumSamples(double alpha) {
        checkAlpha(alpha);
        return (int) Math.ceil(1 / alpha - 1e-12) - 1;
    }

    /**
     * @param genuineScores fused scores (higher = more live) of genuine sessions, in any order
     * @param alpha the BPCER to guarantee, in (0, 1)
     */
    public static ConformalThreshold calibrate(double[] genuineScores, double alpha) {
        checkAlpha(alpha);
        double[] s = genuineScores.clone();
        Arrays.sort(s);
        int n = s.length;
        int k = (int) Math.floor(alpha * (n + 1) + 1e-12);
        double tau = k < 1 ? Double.NEGATIVE_INFINITY : s[k - 1];
        return new ConformalThreshold(alpha, n, k, tau);
    }

    /** One calibration per group, each from that group's own genuine scores. */
    public static Map<String, ConformalThreshold> byGroup(Map<String, double[]> genuineScoresByGroup, double alpha) {
        Map<String, ConformalThreshold> out = new LinkedHashMap<>();
        genuineScoresByGroup.forEach((g, scores) -> out.put(g, calibrate(scores, alpha)));
        return out;
    }

    /** True if a rejection rule can be guaranteed at all; false means "reject nobody on score" is the only safe rule. */
    public boolean canReject() {
        return k >= 1;
    }

    /** Reject at or below this score; negative infinity when {@link #canReject()} is false. */
    public double threshold() {
        return tau;
    }

    /** Accept a session with this fused score: false exactly when the score is at or below the threshold. */
    public boolean accepts(double score) {
        return !(canReject() && score <= tau);
    }

    /** The BPCER bound that actually holds: {@code k / (n + 1)}, never above {@code alpha}. */
    public double guaranteedBpcer() {
        return (double) k / (n + 1);
    }

    public int samples() {
        return n;
    }

    public double alpha() {
        return alpha;
    }

    /** One sentence saying what is and is not promised, for a report. */
    public String statement() {
        if (!canReject()) {
            return "with " + n + " genuine samples a BPCER of " + alpha + " cannot be guaranteed by a score rule (needs at"
                    + " least " + minimumSamples(alpha) + "); no session may be rejected on this score alone";
        }
        return String.format("a genuine resident, drawn like the %d calibration samples, is rejected with probability at"
                + " most %.4f (<= %.4f); says nothing about attacks, or about a station that drifts", n, guaranteedBpcer(),
                alpha);
    }

    private static void checkAlpha(double alpha) {
        if (!(alpha > 0 && alpha < 1)) {
            throw new IllegalArgumentException("alpha must be in (0, 1), got " + alpha);
        }
    }
}
