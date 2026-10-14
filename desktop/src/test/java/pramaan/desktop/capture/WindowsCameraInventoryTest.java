/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WindowsCameraInventoryTest {

    @Test
    void thePowerShellOutputBecomesOneNamePerLine() {
        WindowsCameraInventory inventory = new WindowsCameraInventory(
                () -> Optional.of("Integrated Webcam\r\n\r\nOBS Virtual Camera\r\nIntegrated Webcam\r\n  \r\n"));
        assertEquals(Optional.of(List.of("Integrated Webcam", "OBS Virtual Camera")), inventory.cameraNames());
    }

    @Test
    void whenPowerShellCannotBeAskedTheAnswerIsUnknownNotNone() {
        assertTrue(new WindowsCameraInventory(Optional::empty).cameraNames().isEmpty());
    }

    @Test
    void anEmptyOutputIsAKnownEmptyList() {
        assertEquals(Optional.of(List.of()), new WindowsCameraInventory(() -> Optional.of("")).cameraNames());
    }
}
