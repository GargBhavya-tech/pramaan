/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.image;

import java.util.Objects;

/** A decoded image: interleaved 8-bit RGB, row-major. Decoding happens outside core (no javax.imageio on Android). */
public final class RgbImage {

    private final int width;
    private final int height;
    private final byte[] rgb;

    private RgbImage(int width, int height, byte[] rgb) {
        this.width = width;
        this.height = height;
        this.rgb = rgb;
    }

    /** Wraps pixels; ownership passes to the image. */
    public static RgbImage of(int width, int height, byte[] rgb) {
        Objects.requireNonNull(rgb, "rgb");
        if (width <= 0 || height <= 0 || (long) width * height * 3 != rgb.length) {
            throw new IllegalArgumentException("need " + width + "x" + height + "x3 bytes, got " + rgb.length);
        }
        return new RgbImage(width, height, rgb);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Channel value 0-255; channel 0 = R, 1 = G, 2 = B. */
    public int get(int x, int y, int channel) {
        return rgb[(y * width + x) * 3 + channel] & 0xFF;
    }

    byte[] raw() {
        return rgb;
    }
}
