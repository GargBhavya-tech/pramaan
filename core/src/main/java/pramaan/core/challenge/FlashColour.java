/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

/**
 * The flash palette (Bible Part 8.6): six colours, deliberately without saturated red, whose alternation
 * is a photosensitivity trigger. Components are nominal linear RGB in [0, 1].
 */
public enum FlashColour {
    WHITE(1, 1, 1),
    YELLOW(1, 1, 0.2),
    GREEN(0.2, 1, 0.2),
    CYAN(0.2, 1, 1),
    BLUE(0.2, 0.3, 1),
    MAGENTA(1, 0.3, 1);

    private final double r;
    private final double g;
    private final double b;

    FlashColour(double r, double g, double b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public double r() {
        return r;
    }

    public double g() {
        return g;
    }

    public double b() {
        return b;
    }
}
