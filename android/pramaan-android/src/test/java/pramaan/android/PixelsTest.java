/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import pramaan.core.image.RgbImage;

/** Runs on the build machine: the ARGB-to-RGB step every Android frame and capture passes through. */
public class PixelsTest {

    @Test
    public void packedArgbBecomesRgbInRowOrderAndAlphaIsDropped() {
        int[] argb = {0xFF102030, 0x80A0B0C0, 0x00FFFFFF, 0xFF000000, 0xFF7F8081, 0xFFFF0000};
        RgbImage img = Pixels.fromArgb(argb, 3, 2);
        assertEquals(3, img.width());
        assertEquals(2, img.height());
        assertEquals(0x10, img.get(0, 0, 0));
        assertEquals(0x20, img.get(0, 0, 1));
        assertEquals(0x30, img.get(0, 0, 2));
        assertEquals(0xA0, img.get(1, 0, 0));
        assertEquals(0xC0, img.get(1, 0, 2));
        assertEquals(0xFF, img.get(2, 0, 1));
        assertEquals(0x7F, img.get(1, 1, 0));
        assertEquals(0x81, img.get(1, 1, 2));
        assertEquals(0xFF, img.get(2, 1, 0));
        assertEquals(0, img.get(2, 1, 1));
    }

    @Test
    public void aPixelCountThatDoesNotFitIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Pixels.fromArgb(new int[5], 3, 2));
    }
}
