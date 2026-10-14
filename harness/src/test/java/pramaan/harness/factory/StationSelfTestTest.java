/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The install-time self-test (Bible Part 43, 45), on a synthetic room. SYNTHETIC. */
class StationSelfTestTest {

    private static final List<StationSelfTest.Row> ROWS = StationSelfTest.run(SyntheticScene.room(7).image());

    private static StationSelfTest.Row row(String startsWith) {
        return ROWS.stream().filter(r -> r.name.startsWith(startsWith)).findFirst().orElseThrow();
    }

    @Test
    void everyControlIsCalledLiveIncludingAfterAnExposureChangeAndMovedFurniture() {
        for (StationSelfTest.Row r : ROWS) {
            if (r.shouldBeLive) {
                assertFalse(r.wrong, r.name + " scene " + r.scenePLive + " moire " + r.moirePLive);
            }
        }
    }

    @Test
    void aDifferentRoomIsNotLive() {
        StationSelfTest.Row r = row("a different room");
        assertTrue(r.scenePLive < 0.3, "scene " + r.scenePLive);
        assertFalse(r.wrong);
    }

    @Test
    void aScreenReplayOfTheRightRoomFoolsTheSceneCueSoTheMoireCueMustCatchIt() {
        StationSelfTest.Row r = row("screen replay of this room, pitch 1.40");
        assertFalse(r.wrong);
        assertTrue(r.moirePLive < 0.3, "moire " + r.moirePLive);
    }

    /** R-01 end to end: at a matching pitch the replay is the right room and shows no moire, so both cues say live. */
    @Test
    void theKnownMoireBlindSpotShowsUpAsAReplayThatFoolsBothCues() {
        StationSelfTest.Row r = row("screen replay of this room, pitch 1.00");
        assertTrue(r.wrong, "scene " + r.scenePLive + " moire " + r.moirePLive);
    }
}
