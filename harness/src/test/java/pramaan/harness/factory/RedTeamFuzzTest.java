/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The automated red team (Bible Part 45), SYNTHETIC. The slow search is run from the command line; these tests pin the
 * finding it made and the properties of the search, with a handful of evaluations each.
 */
class RedTeamFuzzTest {

    private static final RedTeamFuzz FUZZ = new RedTeamFuzz();

    private static RedTeamFuzz.Attack attack(double pitch, double leak, double gap, double blur) {
        return new RedTeamFuzz.Attack(new double[] {pitch, leak, gap, blur});
    }

    @Test
    void aNaiveRecaptureIsCaughtByTheMoireCue() {
        RedTeamFuzz.Outcome o = FUZZ.evaluate(attack(1.15, 0.12, 0.08, 0));
        assertFalse(o.abstained);
        assertTrue(o.pLive < 0.5, "pLive " + o.pLive);
    }

    /**
     * R-01, measured by sweeping the pitch (docs/red-team-log.md): the cue looks at spatial frequencies from 12 to 60
     * cycles per 128 pixels and ignores bins on the 8-pixel JPEG lattice, so a display whose grid aliases to DC (pitch 1.0,
     * or any ratio where the pixel and its sub-pixel stripes both land on whole cycles) or onto the lattice (pitch 0.8,
     * 1.2, 1.6) shows no moire to it. The holes are narrow: a percent or so of pitch.
     */
    @Test
    void aFewNarrowPitchesSlipPastTheCue() {
        for (double pitch : new double[] {1.0, 0.8, 1.2}) {
            RedTeamFuzz.Outcome o = FUZZ.evaluate(attack(pitch, 0.0, 0.15, 0));
            assertFalse(o.abstained);
            assertTrue(o.pLive > 0.9, "pitch " + pitch + " pLive " + o.pLive);
        }
    }

    @Test
    void theHolesAreNarrowAndMostPitchesAreCaught() {
        for (double pitch : new double[] {0.9, 1.1, 1.3, 1.45, 1.7, 1.8}) {
            RedTeamFuzz.Outcome o = FUZZ.evaluate(attack(pitch, 0.0, 0.15, 0));
            assertTrue(o.abstained || o.pLive < 0.5, "pitch " + pitch + " pLive " + o.pLive);
        }
    }

    @Test
    void theHoleDoesNotNeedABlurSoNoSharpnessCheckCoversIt() {
        RedTeamFuzz.Outcome o = FUZZ.evaluate(attack(1.0, 0.0, 0.15, 0));
        assertTrue(o.sharpnessRatio > 0.9, "the recapture is not blurrier than the source: x" + o.sharpnessRatio);
    }

    @Test
    void blurringDoesNotHelpTheAttackerAgainstThisCue() {
        RedTeamFuzz.Outcome clean = FUZZ.evaluate(attack(1.0, 0.0, 0.15, 0));
        RedTeamFuzz.Outcome blurred = FUZZ.evaluate(attack(1.0, 0.0, 0.15, 2.0));
        assertTrue(blurred.pLive < clean.pLive, "blur closes the hole: " + clean.pLive + " -> " + blurred.pLive);
        assertTrue(blurred.sharpnessRatio < clean.sharpnessRatio / 3, "and costs sharpness: x" + blurred.sharpnessRatio);
    }

    @Test
    void theSearchNeverReturnsAWorseAttackThanItStartedWith() {
        RedTeamFuzz.Result r = FUZZ.search(3, 2, 0);
        assertTrue(r.bestOutcome.abstained || r.bestOutcome.peakiness <= r.naiveOutcome.peakiness + 1e-9);
        assertEquals(1 + 2 * 12, r.evaluations);
    }

    @Test
    void theSearchIsDeterministicInItsSeed() {
        RedTeamFuzz.Result a = FUZZ.search(5, 1, 0);
        RedTeamFuzz.Result b = FUZZ.search(5, 1, 0);
        assertEquals(a.best.toString(), b.best.toString());
    }
}
