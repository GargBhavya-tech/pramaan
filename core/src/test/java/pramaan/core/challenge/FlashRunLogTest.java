/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The host's record of a recorded flash experiment (docs/research-plan-real-data.md). */
class FlashRunLogTest {

    private static final long T0 = 1_760_000_000_000L;

    private static FlashRunLog log() {
        return new FlashRunLog("genuine", "Integrated Camera", T0, List.of(
                new FlashRunLog.Step(FlashColour.GREEN, T0 + 700, 400),
                new FlashRunLog.Step(FlashColour.BLUE, T0 + 1100, 450),
                new FlashRunLog.Step(FlashColour.WHITE, T0 + 1560, 400)), T0 + 1960, true);
    }

    @Test
    void theColourOnScreenFollowsWhatWasShownNotWhatWasPlanned() {
        FlashRunLog l = log();
        assertFalse(l.colourAt(T0 + 699).isPresent(), "dark before the first colour");
        assertEquals(FlashColour.GREEN, l.colourAt(T0 + 700).orElseThrow());
        assertEquals(FlashColour.GREEN, l.colourAt(T0 + 1099).orElseThrow());
        assertEquals(FlashColour.BLUE, l.colourAt(T0 + 1100).orElseThrow());
        // BLUE was asked for 450 ms but WHITE came 460 ms later: dark for the 10 ms between
        assertFalse(l.colourAt(T0 + 1555).isPresent());
        assertEquals(FlashColour.WHITE, l.colourAt(T0 + 1700).orElseThrow());
        assertFalse(l.colourAt(T0 + 1960).isPresent(), "dark from the logged off time");
    }

    @Test
    void aStepCutShortByTheNextOneEndsWhenTheNextBegins() {
        FlashRunLog l = new FlashRunLog("x", "c", T0, List.of(new FlashRunLog.Step(FlashColour.CYAN, T0 + 100, 1000),
                new FlashRunLog.Step(FlashColour.YELLOW, T0 + 300, 400)), T0 + 800, true);
        assertEquals(FlashColour.CYAN, l.colourAt(T0 + 299).orElseThrow());
        assertEquals(FlashColour.YELLOW, l.colourAt(T0 + 300).orElseThrow());
    }

    @Test
    void theLogSurvivesItsFile() {
        FlashRunLog back = FlashRunLog.fromJson(log().toJson());
        assertEquals("genuine", back.label());
        assertEquals("Integrated Camera", back.camera());
        assertEquals(3, back.steps().size());
        assertEquals(FlashColour.BLUE, back.steps().get(1).colour());
        assertEquals(T0 + 1100, back.steps().get(1).shownAtEpochMs());
        assertEquals(T0 + 1960, back.offAtEpochMs());
        assertTrue(back.windowShown());
        assertEquals(T0 + 700, back.firstColourAtEpochMs());
    }

    @Test
    void aDryRunSaysNoLightWasShown() {
        FlashRunLog dry = new FlashRunLog("dry", "none", T0, List.of(), T0 + 10, false);
        assertFalse(FlashRunLog.fromJson(dry.toJson()).windowShown());
        assertEquals(T0, dry.firstColourAtEpochMs());
    }

    @Test
    void stepsOutOfOrderAndFilesThatAreNotLogsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new FlashRunLog("x", "c", T0, List.of(
                new FlashRunLog.Step(FlashColour.GREEN, T0 + 500, 400),
                new FlashRunLog.Step(FlashColour.BLUE, T0 + 100, 400)), T0 + 900, true));
        assertThrows(IllegalArgumentException.class, () -> FlashRunLog.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> FlashRunLog.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> FlashRunLog.fromJson(
                "{\"version\":1,\"steps\":[{\"colour\":\"RED\",\"shownAtEpochMs\":1,\"durationMs\":1}]}"));
    }
}
