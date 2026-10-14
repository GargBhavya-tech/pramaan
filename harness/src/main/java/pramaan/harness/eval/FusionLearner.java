/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pramaan.core.fusion.ConformalThreshold;

/**
 * Learns the fusion weights from labelled sessions instead of writing them by hand (Bible Part 9, 24; Build Map #35),
 * in exactly the shape the engine already runs: {@code L = b + sum over voting cues of wPos * max(logit, 0) + wNeg *
 * min(logit, 0)}. Because the model is the engine's own, whatever is learned drops into {@code operating-points.json}
 * and nothing else changes. Three rules from the hand-written fusion are kept, not learned:
 * <ul>
 *   <li>weights are never negative (a cue that says "live" can never count as evidence of attack: monotonicity);</li>
 *   <li>an abstaining cue is not in the sum, so a missing cue is "unknown", never "spoof";</li>
 *   <li>a physiological cue's {@code wNeg} stays zero for residents.</li>
 * </ul>
 *
 * <p>Training is gradient descent on a class-balanced logistic loss, pulled toward the hand-set weights by an L2 penalty
 * (shrinkage): with little data the result stays near what was written down, with plenty it moves. The cut-off on the
 * fused score is chosen separately by {@link ConformalThreshold}, so the BPCER is guaranteed and the weights only decide
 * how well attacks are separated from genuine sessions.
 *
 * <p>Output fragment, in the shape of {@code fusion} in {@code operating-points.json}:
 * <pre>{"bias": 0.0, "cues": {"moire": {"wPos": 0.8, "wNeg": 0.8, "physiological": false}}}</pre>
 *
 * <p>Honest status: there is no real corpus (Build Map #65). The tests train on synthetic votes, to show the method
 * moves weights the right way and that its guarantee holds; weights learned from synthetic attacks must not be shipped.
 */
public final class FusionLearner {

    /** One labelled session: each voting cue's pLive; a cue that abstained is simply absent. */
    public static final class Sample {
        final boolean genuine;
        final Map<String, Double> votes;

        public Sample(boolean genuine, Map<String, Double> votes) {
            this.genuine = genuine;
            this.votes = Map.copyOf(votes);
        }
    }

    /** The fusion table in the engine's shape: bias, and wPos and wNeg per cue. */
    public static final class Weights {
        public final double bias;
        public final Map<String, double[]> perCue; // {wPos, wNeg}

        public Weights(double bias, Map<String, double[]> perCue) {
            this.bias = bias;
            Map<String, double[]> copy = new LinkedHashMap<>();
            perCue.forEach((k, v) -> copy.put(k, v.clone()));
            this.perCue = copy;
        }

        /** The {@code fusion} fragment of {@code operating-points.json}. */
        public String toJson(Set<String> physiological) {
            StringBuilder sb = new StringBuilder("{\n  \"bias\": ").append(String.format("%.4f", bias)).append(",\n  \"cues\": {");
            boolean first = true;
            for (Map.Entry<String, double[]> e : perCue.entrySet()) {
                sb.append(first ? "\n" : ",\n").append(String.format(
                        "    \"%s\": {\"wPos\": %.4f, \"wNeg\": %.4f, \"physiological\": %b}", e.getKey(), e.getValue()[0],
                        e.getValue()[1], physiological.contains(e.getKey())));
                first = false;
            }
            return sb.append("\n  }\n}").toString();
        }
    }

    private static final double CLAMP = 6.0;

    private FusionLearner() {
    }

    /** The fused log-odds of one session under {@code w}, as {@code RiskFusion} computes it for a resident. */
    public static double score(Weights w, Sample s, Set<String> physiological) {
        double l = w.bias;
        for (Map.Entry<String, Double> v : s.votes.entrySet()) {
            double[] cw = w.perCue.get(v.getKey());
            if (cw == null) {
                continue;
            }
            double logit = logit(v.getValue());
            double weight = logit >= 0 ? cw[0] : (physiological.contains(v.getKey()) ? 0 : cw[1]);
            l += weight * logit;
        }
        return l;
    }

    /**
     * @param prior the hand-set weights to shrink toward
     * @param l2 shrinkage strength: 0 learns freely, large keeps the prior
     * @param epochs full passes over the data
     * @param rate gradient step
     */
    public static Weights learn(List<Sample> data, Weights prior, Set<String> physiological, double l2, int epochs,
            double rate) {
        double bias = prior.bias;
        Map<String, double[]> w = new LinkedHashMap<>();
        prior.perCue.forEach((k, v) -> w.put(k, v.clone()));
        long genuine = data.stream().filter(s -> s.genuine).count();
        long attack = data.size() - genuine;
        if (genuine == 0 || attack == 0) {
            throw new IllegalArgumentException("learning needs both genuine and attack sessions");
        }
        double wGenuine = 0.5 * data.size() / genuine;
        double wAttack = 0.5 * data.size() / attack;
        for (int e = 0; e < epochs; e++) {
            double gBias = 0;
            Map<String, double[]> grad = new LinkedHashMap<>();
            w.keySet().forEach(k -> grad.put(k, new double[2]));
            for (Sample s : data) {
                double y = s.genuine ? 1 : -1;
                double cw = s.genuine ? wGenuine : wAttack;
                double l = bias;
                for (Map.Entry<String, Double> v : s.votes.entrySet()) {
                    double[] cwts = w.get(v.getKey());
                    if (cwts != null) {
                        double lg = logit(v.getValue());
                        l += (lg >= 0 ? cwts[0] : (physiological.contains(v.getKey()) ? 0 : cwts[1])) * lg;
                    }
                }
                double dl = -y * sigmoid(-y * l) * cw / data.size(); // d loss / d L
                gBias += dl;
                for (Map.Entry<String, Double> v : s.votes.entrySet()) {
                    double[] g = grad.get(v.getKey());
                    if (g == null) {
                        continue;
                    }
                    double lg = logit(v.getValue());
                    if (lg >= 0) {
                        g[0] += dl * lg;
                    } else if (!physiological.contains(v.getKey())) {
                        g[1] += dl * lg;
                    }
                }
            }
            bias -= rate * gBias;
            for (Map.Entry<String, double[]> en : w.entrySet()) {
                double[] cur = en.getValue();
                double[] p0 = prior.perCue.get(en.getKey());
                double[] g = grad.get(en.getKey());
                for (int i = 0; i < 2; i++) {
                    if (i == 1 && physiological.contains(en.getKey())) {
                        cur[1] = 0; // never counts against a resident
                        continue;
                    }
                    // gradient step on the loss, then the exact (proximal) step for the pull toward the prior, which stays
                    // stable however strong the shrinkage is; a plain gradient step on the penalty overshoots above 1
                    double pull = 2 * rate * l2;
                    cur[i] = Math.max(0, (cur[i] - rate * g[i] + pull * p0[i]) / (1 + pull));
                }
            }
        }
        return new Weights(bias, w);
    }

    /** Area under the ROC curve: the chance a genuine session outscores an attack (ties count half). */
    public static double auc(List<Sample> data, Weights w, Set<String> physiological) {
        List<Double> g = new ArrayList<>();
        List<Double> a = new ArrayList<>();
        for (Sample s : data) {
            (s.genuine ? g : a).add(score(w, s, physiological));
        }
        double wins = 0;
        for (double x : g) {
            for (double y : a) {
                wins += x > y ? 1 : x == y ? 0.5 : 0;
            }
        }
        return wins / ((double) g.size() * a.size());
    }

    /** What a held-out comparison says: both weight sets, scored the same way, with the conformal cut-off. */
    public static String report(List<Sample> calibration, List<Sample> test, Weights hand, Weights learned,
            Set<String> physiological, double alpha) {
        return line("hand-set", calibration, test, hand, physiological, alpha) + "\n"
                + line("learned", calibration, test, learned, physiological, alpha) + "\n";
    }

    private static String line(String name, List<Sample> calibration, List<Sample> test, Weights w, Set<String> phys,
            double alpha) {
        double[] genuine = calibration.stream().filter(s -> s.genuine).mapToDouble(s -> score(w, s, phys)).toArray();
        ConformalThreshold t = ConformalThreshold.calibrate(genuine, alpha);
        long attacks = test.stream().filter(s -> !s.genuine).count();
        long passed = test.stream().filter(s -> !s.genuine && t.accepts(score(w, s, phys))).count();
        Binomial.Rate ci = Binomial.clopperPearson(passed, attacks, 0.95);
        return String.format("%-9s AUC %.3f   conformal cut-off %.2f   APCER %d/%d = %.3f [%.3f, %.3f]   (%s)", name,
                auc(test, w, phys), t.threshold(), passed, attacks, ci.value(), ci.lower(), ci.upper(), t.statement());
    }

    private static double logit(double p) {
        double q = Math.max(1e-3, Math.min(1 - 1e-3, p));
        return Math.max(-CLAMP, Math.min(CLAMP, Math.log(q / (1 - q))));
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }
}
