/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pramaan.core.fusion.ConformalThreshold;

/**
 * The learned fusion (Bible Part 9, 24), on SYNTHETIC votes: one cue the attacks fool (it votes the same for genuine
 * sessions and for attacks), one that separates them well, one that is often missing, and a physiological cue that may
 * only ever help. Shows the method moves weights the right way and keeps the three rules; says nothing about real cues.
 */
class FusionLearnerTest {

    private static final Set<String> PHYSIOLOGICAL = Set.of("plr");

    private static FusionLearner.Weights hand() {
        Map<String, double[]> w = new LinkedHashMap<>();
        w.put("passive", new double[] {1.0, 1.0});
        w.put("moire", new double[] {0.8, 0.8});
        w.put("flash", new double[] {1.5, 1.5});
        w.put("plr", new double[] {1.0, 0.0});
        return new FusionLearner.Weights(0.0, w);
    }

    private static double p(double logit) {
        return 1 / (1 + Math.exp(-logit));
    }

    /** @param moireInformative whether attacks lower the moire cue's vote (false: attacks fool it) */
    private static List<FusionLearner.Sample> corpus(long seed, int n, boolean moireInformative) {
        Random r = new Random(seed);
        List<FusionLearner.Sample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean genuine = i % 2 == 0;
            Map<String, Double> v = new LinkedHashMap<>();
            v.put("passive", p(genuine ? 1.5 + r.nextGaussian() : -0.3 + 1.2 * r.nextGaussian()));
            v.put("moire", p(genuine || !moireInformative ? 1.5 + r.nextGaussian() : -1.5 + r.nextGaussian()));
            if (r.nextDouble() < 0.5) { // the flash cue is missing on half the sessions: abstained
                v.put("flash", p(genuine ? 2.0 + r.nextGaussian() : -2.5 + r.nextGaussian()));
            }
            if (genuine && r.nextDouble() < 0.3) { // a reflex only ever shows up for a live eye
                v.put("plr", p(1 + r.nextGaussian()));
            }
            out.add(new FusionLearner.Sample(genuine, v));
        }
        return out;
    }

    private static FusionLearner.Weights learn(List<FusionLearner.Sample> data, double l2) {
        return FusionLearner.learn(data, hand(), PHYSIOLOGICAL, l2, 400, 0.5);
    }

    @Test
    void aCueTheAttacksFoolLosesWeightAndOnHeldOutSessionsTheLearnedFusionSeparatesBetter() {
        List<FusionLearner.Sample> train = corpus(1, 2000, false);
        List<FusionLearner.Sample> test = corpus(2, 2000, false);
        FusionLearner.Weights learned = learn(train, 0.01);
        assertTrue(learned.perCue.get("moire")[0] < 0.8 * 0.6, "moire weight " + learned.perCue.get("moire")[0]);
        assertTrue(learned.perCue.get("flash")[0] >= hand().perCue.get("flash")[0] * 0.9,
                "the cue that separates is kept: " + learned.perCue.get("flash")[0]);
        double handAuc = FusionLearner.auc(test, hand(), PHYSIOLOGICAL);
        double learnedAuc = FusionLearner.auc(test, learned, PHYSIOLOGICAL);
        assertTrue(learnedAuc > handAuc, "AUC " + handAuc + " -> " + learnedAuc);
    }

    @Test
    void aCueThatDoesSeparateIsNotDownweighted() {
        FusionLearner.Weights learned = learn(corpus(1, 2000, true), 0.01);
        assertTrue(learned.perCue.get("moire")[1] >= 0.8 * 0.8, "moire wNeg " + learned.perCue.get("moire")[1]);
    }

    @Test
    void theThreeRulesOfTheHandWrittenFusionSurviveLearning() {
        FusionLearner.Weights learned = learn(corpus(5, 1500, false), 0.01);
        for (double[] w : learned.perCue.values()) {
            assertTrue(w[0] >= 0 && w[1] >= 0, "weights are never negative");
        }
        assertEquals(0.0, learned.perCue.get("plr")[1], 0.0, "a physiological cue never counts against a resident");
        // a session with a missing cue is still scored, and a missing cue contributes nothing
        FusionLearner.Sample without = new FusionLearner.Sample(true, Map.of("passive", p(2)));
        FusionLearner.Sample with = new FusionLearner.Sample(true, Map.of("passive", p(2), "flash", p(0.0)));
        assertEquals(FusionLearner.score(learned, without, PHYSIOLOGICAL), FusionLearner.score(learned, with, PHYSIOLOGICAL),
                1e-9, "a flash vote of exactly 0.5 adds nothing; absent adds nothing");
    }

    @Test
    void withHeavyShrinkageAndLittleDataTheLearnedWeightsStayNearTheHandSetOnes() {
        FusionLearner.Weights learned = FusionLearner.learn(corpus(7, 40, false), hand(), PHYSIOLOGICAL, 50.0, 200, 0.1);
        assertEquals(0.8, learned.perCue.get("moire")[0], 0.1);
        assertEquals(1.5, learned.perCue.get("flash")[0], 0.15);
    }

    @Test
    void theConformalCutOffKeepsTheGenuineRejectionRateAtOrBelowAlphaOnHeldOutSessions() {
        FusionLearner.Weights learned = learn(corpus(1, 2000, false), 0.01);
        List<FusionLearner.Sample> cal = corpus(3, 4000, false);
        List<FusionLearner.Sample> test = corpus(4, 8000, false);
        double[] genuineScores = cal.stream().filter(s -> s.genuine).mapToDouble(
                s -> FusionLearner.score(learned, s, PHYSIOLOGICAL)).toArray();
        ConformalThreshold t = ConformalThreshold.calibrate(genuineScores, 0.05);
        long genuine = test.stream().filter(s -> s.genuine).count();
        long rejected = test.stream().filter(s -> s.genuine && !t.accepts(FusionLearner.score(learned, s, PHYSIOLOGICAL)))
                .count();
        assertTrue((double) rejected / genuine <= 0.05 + 0.015, "BPCER " + (double) rejected / genuine);
    }

    @Test
    void theReportComparesBothWeightSetsWithIntervals() {
        FusionLearner.Weights learned = learn(corpus(1, 2000, false), 0.01);
        String report = FusionLearner.report(corpus(3, 2000, false), corpus(4, 2000, false), hand(), learned, PHYSIOLOGICAL,
                0.05);
        assertTrue(report.contains("hand-set") && report.contains("learned"), report);
        assertTrue(report.contains("APCER") && report.contains("["), report);
    }

    @Test
    void thePrintedFragmentHasTheShapeOfTheOperatingPointTable() {
        String json = hand().toJson(PHYSIOLOGICAL);
        assertTrue(json.contains("\"plr\": {\"wPos\": 1.0000, \"wNeg\": 0.0000, \"physiological\": true}"), json);
        assertTrue(json.contains("\"bias\""), json);
    }
}
