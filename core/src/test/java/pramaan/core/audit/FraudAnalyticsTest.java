/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.audit.FraudAnalytics.Signal;

class FraudAnalyticsTest {

    @Test
    void theBiblesWorkedExample() {
        // 6 PAD fails in 40 attempts against a 2% centre rate: (6 - 0.8) / sqrt(40 * 0.02 * 0.98) = 5.87.
        assertEquals(5.87, FraudAnalytics.zScore(6, 40, 0.02), 0.01);
        double[] lim = FraudAnalytics.funnelLimits(40, 0.02, 3);
        assertEquals(0.0, lim[0], 1e-12);
        assertEquals(0.02 + 3 * Math.sqrt(0.02 * 0.98 / 40), lim[1], 1e-12);
    }

    @Test
    void anOperatorFailingFarMoreThanTheirCentreIsFlaggedAndTheOthersAreNot() {
        List<FraudAnalytics.Attempt> attempts = new ArrayList<>();
        // Nine honest operators, 100 attempts each, 2 PAD fails each; one with 6 in 40.
        for (int op = 0; op < 9; op++) {
            for (int i = 0; i < 100; i++) {
                attempts.add(new FraudAnalytics.Attempt("op" + op, "C1",
                        i < 2 ? EnumSet.of(Signal.PAD_FAIL) : EnumSet.noneOf(Signal.class)));
            }
        }
        for (int i = 0; i < 40; i++) {
            attempts.add(new FraudAnalytics.Attempt("mallory", "C1",
                    i < 6 ? EnumSet.of(Signal.PAD_FAIL, Signal.ACCESSIBILITY) : EnumSet.noneOf(Signal.class)));
        }
        List<FraudAnalytics.Line> lines = FraudAnalytics.analyse(attempts);
        assertEquals(10 * Signal.values().length, lines.size());
        for (FraudAnalytics.Line l : lines) {
            boolean shouldFlag = l.operatorId.equals("mallory") && (l.signal == Signal.PAD_FAIL || l.signal == Signal.ACCESSIBILITY);
            assertEquals(shouldFlag, l.flagged(), l.operatorId + " " + l.signal + " z=" + l.z);
        }
    }

    @Test
    void eachOperatorIsJudgedAgainstTheirOwnCentre() {
        List<FraudAnalytics.Attempt> attempts = new ArrayList<>();
        // A centre where 15% of attempts fail (a bad camera, say) does not make its operators look like fraudsters.
        for (int op = 0; op < 5; op++) {
            for (int i = 0; i < 40; i++) {
                attempts.add(new FraudAnalytics.Attempt("b" + op, "C2",
                        i < 6 ? EnumSet.of(Signal.PAD_FAIL) : EnumSet.noneOf(Signal.class)));
            }
        }
        for (int i = 0; i < 400; i++) {
            attempts.add(new FraudAnalytics.Attempt("a", "C1",
                    i < 8 ? EnumSet.of(Signal.PAD_FAIL) : EnumSet.noneOf(Signal.class)));
        }
        for (FraudAnalytics.Line l : FraudAnalytics.analyse(attempts)) {
            assertFalse(l.flagged(), l.operatorId + " " + l.signal);
            if (l.centreId.equals("C2") && l.signal == Signal.PAD_FAIL) {
                assertEquals(0.15, l.centreRate, 1e-12);
            }
        }
    }

    @Test
    void noVariationMeansNoScore() {
        assertEquals(0, FraudAnalytics.zScore(0, 50, 0));
        assertEquals(0, FraudAnalytics.zScore(50, 50, 1));
        assertTrue(FraudAnalytics.funnelLimits(0, 0.1, 3)[1] == 1);
    }
}
