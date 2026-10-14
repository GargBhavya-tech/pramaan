/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Physics as a labeller (Bible Part 8.6, 24), on SYNTHETIC sessions with a teacher blind spot built in. */
class PseudoLabelerTest {

    private static PseudoLabeler.Session s(String id, double flash, double passive, Boolean human) {
        return new PseudoLabeler.Session(id, Map.of("flash", flash, "passive", passive), human);
    }

    @Test
    void onlyAConfidentFlashVoteLabelsAndAMissingOrWeakOneDoesNot() {
        assertEquals(PseudoLabeler.Label.LIVE, PseudoLabeler.label(s("a", 0.95, 0.9, null)));
        assertEquals(PseudoLabeler.Label.ATTACK, PseudoLabeler.label(s("b", 0.04, 0.2, null)));
        assertEquals(PseudoLabeler.Label.UNSURE, PseudoLabeler.label(s("c", 0.7, 0.9, null)));
        assertEquals(PseudoLabeler.Label.UNSURE, PseudoLabeler.label(new PseudoLabeler.Session("d", Map.of("passive", 0.9), null)));
    }

    @Test
    void aSessionWhereAnotherCueDisagreesWithTheTeacherIsAlwaysSentToAHuman() {
        // the teacher says live (flash 0.97), but passive is sure it is an attack: a projected mask, say
        List<PseudoLabeler.Session> all = List.of(s("blind", 0.97, 0.05, null), s("fine", 0.97, 0.95, null));
        PseudoLabeler.Result r = PseudoLabeler.run(all, 0.0, 1);
        assertTrue(r.reviewQueue.contains("blind"));
        assertFalse(r.reviewQueue.contains("fine"), "no audit share, no disagreement: not queued");
        assertEquals(2, r.live.size());
    }

    @Test
    void aRandomAuditShareAlsoQueuesAgreeingSessionsToCatchSharedBlindSpots() {
        List<PseudoLabeler.Session> all = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            all.add(s("x" + i, 0.97, 0.95, null));
        }
        int queued = PseudoLabeler.run(all, 0.10, 7).reviewQueue.size();
        assertTrue(queued > 20 && queued < 60, "about 10% of 400: " + queued);
    }

    /**
     * The estimate of the teacher's error must come from the random audit, not from the disagreement queue, which is
     * chosen for being suspicious and so overstates the error. Both are computed to show the difference.
     */
    @Test
    void theTeachersErrorIsEstimatedWithAnIntervalAndTheQueueAloneOverstatesIt() {
        Random r = new Random(5);
        List<PseudoLabeler.Session> randomAudit = new ArrayList<>();
        List<PseudoLabeler.Session> queueOnly = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            boolean projectedMask = r.nextDouble() < 0.03; // the teacher's blind spot: 3% of sessions
            double flash = projectedMask ? 0.96 : (r.nextBoolean() ? 0.97 : 0.03);
            boolean trulyLive = !projectedMask && flash > 0.5;
            double passive = projectedMask ? 0.08 : (trulyLive ? 0.9 : 0.1); // passive, unlike the flash, is not fooled
            PseudoLabeler.Session session = s("s" + i, flash, passive, trulyLive);
            if (r.nextDouble() < 0.15) {
                randomAudit.add(session);
            }
            if (PseudoLabeler.disagrees(session, PseudoLabeler.label(session))) {
                queueOnly.add(session);
            }
        }
        Binomial.Rate unbiased = PseudoLabeler.teacherError(randomAudit);
        Binomial.Rate queued = PseudoLabeler.teacherError(queueOnly);
        assertTrue(unbiased.lower() <= 0.03 && 0.03 <= unbiased.upper() + 0.02,
                "about 3% of sessions are the blind spot (6% of the labelled live ones): " + unbiased.value());
        assertTrue(queued.value() > 0.9, "the queue is nearly all blind spots, so it is not a fair error estimate");
        assertTrue(unbiased.n() > 150);
    }

    @Test
    void aSetIsTrainableOnlyWhenEnoughWereAuditedAndTheErrorBoundIsLow() {
        List<PseudoLabeler.Session> few = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            few.add(s("f" + i, 0.97, 0.9, true));
        }
        assertFalse(PseudoLabeler.trainable(few, 0.10, 200), "20 audited sessions are not enough, however clean");
        List<PseudoLabeler.Session> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(s("m" + i, 0.97, 0.9, true));
        }
        assertTrue(PseudoLabeler.trainable(many, 0.05, 200));
        List<PseudoLabeler.Session> noisy = new ArrayList<>(many);
        for (int i = 0; i < 60; i++) {
            noisy.add(s("n" + i, 0.97, 0.9, false)); // the human says attack, the teacher said live
        }
        assertFalse(PseudoLabeler.trainable(noisy, 0.05, 200), "13% label noise is not trainable at a 5% bound");
    }
}
