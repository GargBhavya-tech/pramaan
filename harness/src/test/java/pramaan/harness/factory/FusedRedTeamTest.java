/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;

/** The red team against the whole decision (red-team log R-03), SYNTHETIC, with the shipped operating-point table. */
class FusedRedTeamTest {

    private static final RgbImage ROOM = SyntheticScene.room(7).image();

    private static FusedRedTeam team() throws Exception {
        return new FusedRedTeam(Path.of("..", "config", "operating-points.json"));
    }

    @Test
    void theThresholdsAreTheShippedOnes() throws Exception {
        assertEquals(0.85, team().tauAccept(), 1e-12);
        assertEquals(0.20, team().tauReject(), 1e-12);
    }

    @Test
    void twoCuesTheAttackerControlsCanSumToAnAcceptanceWithNothingIndependentToStopThem() throws Exception {
        FusedRedTeam t = team();
        double p = t.fusedP(0.90, 0.98, null); // the right room, and no moire seen
        assertTrue(p >= t.tauAccept(), "p " + p);
        assertEquals(FusedRedTeam.Zone.ACCEPT, t.zone(p));
    }

    @Test
    void aFlashCueThatAReplayNeverAnswersTakesTheSameSessionOutOfTheAcceptZone() throws Exception {
        FusedRedTeam t = team();
        double p = t.fusedP(0.90, 0.98, FusedRedTeam.REPLAY_FLASH_VOTE);
        assertTrue(p < t.tauAccept(), "p " + p);
        assertTrue(p > 0.1, "but a single cue does not reject it outright: the challenge decides: " + p);
    }

    @Test
    void theMeasuredBlindPitchesAreAcceptedWithoutTheFlashAndNeverAcceptedWithIt() throws Exception {
        FusedRedTeam t = team();
        for (double pitch : new double[] {0.80, 1.00, 1.20}) {
            double[] v = StationSelfTest.replayVotes(ROOM, pitch);
            assertEquals(FusedRedTeam.Zone.ACCEPT, t.zone(t.fusedP(v[0], v[1], null)), "pitch " + pitch);
            assertTrue(t.zone(t.fusedP(v[0], v[1], FusedRedTeam.REPLAY_FLASH_VOTE)) != FusedRedTeam.Zone.ACCEPT,
                    "pitch " + pitch);
        }
    }

    @Test
    void aPitchTheMoireCueCatchesIsNotAcceptedEvenWithoutTheFlash() throws Exception {
        FusedRedTeam t = team();
        double[] v = StationSelfTest.replayVotes(ROOM, 1.40);
        assertTrue(t.zone(t.fusedP(v[0], v[1], null)) != FusedRedTeam.Zone.ACCEPT);
    }

    @Test
    void theReportCountsTheAcceptancesWithAndWithoutTheFlash() throws Exception {
        FusedRedTeam t = team();
        List<FusedRedTeam.Row> rows = t.sweep(ROOM, 0.98, 1.02, 0.02);
        String report = FusedRedTeam.report(rows, t.tauAccept(), t.tauReject());
        assertTrue(report.contains("accepted without the flash cue"), report);
        assertTrue(report.contains("SYNTHETIC"), report);
    }
}
