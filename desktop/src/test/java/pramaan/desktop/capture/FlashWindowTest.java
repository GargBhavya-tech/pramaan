/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import javafx.geometry.Rectangle2D;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.FlashColour;

/** Build Map #85: the parts of the resident-facing light that do not need a screen. */
class FlashWindowTest {

    @Test
    void theWindowCoversTheMonitorThatWasAskedFor() {
        List<Rectangle2D> monitors = List.of(new Rectangle2D(0, 0, 1920, 1080), new Rectangle2D(1920, 0, 1280, 1024));
        assertEquals(new Rectangle2D(1920, 0, 1280, 1024), FlashWindow.boundsOf(monitors, 1));
    }

    @Test
    void aMonitorThatIsNotThereIsAnErrorAtStartUpNotASilentlyMissingFlash() {
        List<Rectangle2D> one = List.of(new Rectangle2D(0, 0, 1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> FlashWindow.boundsOf(one, 1));
        assertThrows(IllegalArgumentException.class, () -> FlashWindow.boundsOf(one, -1));
    }

    @Test
    void everyPaletteColourIsShownAsItsOwnRgb() {
        for (FlashColour c : FlashColour.values()) {
            Color shown = FlashWindow.toColor(c);
            assertEquals(c.r(), shown.getRed(), 1e-6);
            assertEquals(c.g(), shown.getGreen(), 1e-6);
            assertEquals(c.b(), shown.getBlue(), 1e-6);
        }
    }

    @Test
    void theDarkWindowHidesItselfSoonAfterTheLastColour() {
        assertEquals(1500, FlashWindow.HIDE_AFTER_MS, 0);
    }
}
