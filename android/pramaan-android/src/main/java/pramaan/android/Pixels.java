/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import pramaan.core.image.RgbImage;

/** Android's packed ARGB pixels to core's RGB image: the one place the layouts meet, so it is tested on its own. */
public final class Pixels {

    private Pixels() {
    }

    /** @param argb {@code width * height} pixels, row by row, as {@code Bitmap.getPixels} returns them */
    public static RgbImage fromArgb(int[] argb, int width, int height) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("expected " + width * height + " pixels, got " + argb.length);
        }
        byte[] rgb = new byte[argb.length * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            rgb[3 * i] = (byte) (p >> 16);
            rgb[3 * i + 1] = (byte) (p >> 8);
            rgb[3 * i + 2] = (byte) p;
        }
        return RgbImage.of(width, height, rgb);
    }
}
