/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.List;

/**
 * Photosensitivity gate for a flash sequence (Bible Part 8.6; WCAG 2.3.1 "three flashes"). The generator is built to
 * satisfy this by construction (palette without saturated red, steps of at least 350 ms); this class is the
 * independent check, run on every sequence before it reaches a screen, so a policy edit cannot quietly break it.
 */
public final class FlashSafety {

    /** Most colour changes allowed in any one-second window. */
    public static final int MAX_TRANSITIONS_PER_SECOND = 3;
    /** Shortest permitted colour step, in milliseconds. */
    public static final int MIN_STEP_MS = 333;

    private FlashSafety() {
    }

    /** Relative luminance of a nominal linear-RGB palette colour (Rec. 709 weights). */
    public static double luminance(FlashColour c) {
        return 0.2126 * c.r() + 0.7152 * c.g() + 0.0722 * c.b();
    }

    /** @return null if the sequence is safe, otherwise the reason it is not */
    public static String violation(FlashSequence seq) {
        List<FlashColour> colours = seq.colours();
        int[] d = seq.durationsMs();
        for (int ms : d) {
            if (ms < MIN_STEP_MS) {
                return "a colour step of " + ms + " ms is shorter than " + MIN_STEP_MS + " ms";
            }
        }
        // Transition instants: the boundary between step i and i+1 when the colour actually changes.
        long t = 0;
        long[] changes = new long[colours.size()];
        int n = 0;
        for (int i = 0; i + 1 < colours.size(); i++) {
            t += d[i];
            if (colours.get(i) != colours.get(i + 1)) {
                changes[n++] = t;
            }
        }
        for (int i = 0; i < n; i++) {
            int inWindow = 1;
            for (int j = i + 1; j < n && changes[j] - changes[i] < 1000; j++) {
                inWindow++;
            }
            if (inWindow > MAX_TRANSITIONS_PER_SECOND) {
                return inWindow + " colour changes within one second";
            }
        }
        return null;
    }

    public static boolean safe(FlashSequence seq) {
        return violation(seq) == null;
    }
}
