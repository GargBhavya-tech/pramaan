/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import pramaan.core.source.Capability;
import pramaan.core.source.SessionCapabilities;

/** Build Map #85: the flash timeline and the photosensitivity gate. */
class FlashScheduleTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private static FlashSequence seq(int[] ms, FlashColour... colours) {
        return new FlashSequence(Arrays.asList(colours), ms);
    }

    @Test
    void theLightIsDarkForTheLeadInThenShowsEachColourForItsDuration() {
        FlashSchedule s = new FlashSchedule(seq(new int[] {400, 500}, FlashColour.WHITE, FlashColour.BLUE), T0);
        assertFalse(s.colourAt(T0).isPresent());
        assertFalse(s.colourAt(T0.plusMillis(499)).isPresent());
        assertEquals(FlashColour.WHITE, s.colourAt(T0.plusMillis(500)).get());
        assertEquals(FlashColour.WHITE, s.colourAt(T0.plusMillis(899)).get());
        assertEquals(FlashColour.BLUE, s.colourAt(T0.plusMillis(900)).get());
        assertEquals(FlashColour.BLUE, s.colourAt(T0.plusMillis(1399)).get());
        assertFalse(s.colourAt(T0.plusMillis(1400)).isPresent());
        assertEquals(Duration.ofMillis(1400), s.total());
    }

    @Test
    void everyGeneratedSequenceIsPhotosensitivitySafe() throws Exception {
        for (double fps : new double[] {3, 10, 30}) {
            SessionCapabilities caps = ChallengeGeneratorTest.caps(fps, Capability.RESIDENT_DISPLAY);
            for (int seed = 0; seed < 300; seed++) {
                ChallengePlan plan = ChallengeGenerator.replay(ChallengeGeneratorTest.seed(seed)).plan(
                        ChallengeGeneratorTest.RESIDENT_POOL, 1, 3, caps, AccessibilityAdjustments.NONE);
                FlashSequence f = plan.flash().orElseThrow();
                assertNull(FlashSafety.violation(f), "seed " + seed + " at " + fps + " fps");
            }
        }
    }

    @Test
    void tooManyChangesInASecondIsRefused() {
        FlashSequence fast = seq(new int[] {333, 333, 333, 333, 333}, FlashColour.WHITE, FlashColour.BLUE,
                FlashColour.GREEN, FlashColour.YELLOW, FlashColour.CYAN);
        String why = FlashSafety.violation(fast);
        assertNotNull(why);
        assertTrue(why.contains("within one second"), why);
    }

    @Test
    void aStepShorterThanThePhotosensitivityFloorIsRefused() {
        assertNotNull(FlashSafety.violation(seq(new int[] {150, 150}, FlashColour.WHITE, FlashColour.BLUE)));
    }

    @Test
    void repeatedColoursAreNotTransitions() {
        FlashSequence same = seq(new int[] {340, 340, 340, 340}, FlashColour.WHITE, FlashColour.WHITE,
                FlashColour.WHITE, FlashColour.WHITE);
        assertNull(FlashSafety.violation(same));
    }

    @Test
    void thePaletteHasNoSaturatedRed() {
        for (FlashColour c : FlashColour.values()) {
            assertTrue(!(c.r() > 0.8 && c.g() < 0.4 && c.b() < 0.4), c + " is a saturated red");
        }
    }
}
