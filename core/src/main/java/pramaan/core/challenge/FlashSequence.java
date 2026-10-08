/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A flash colour sequence with a randomised duration per colour (Bible Part 8.6). */
public final class FlashSequence {

    private final List<FlashColour> colours;
    private final int[] durationsMs;

    FlashSequence(List<FlashColour> colours, int[] durationsMs) {
        if (colours.size() != durationsMs.length) {
            throw new IllegalArgumentException("one duration per colour");
        }
        this.colours = Collections.unmodifiableList(colours);
        this.durationsMs = durationsMs.clone();
    }

    public List<FlashColour> colours() {
        return colours;
    }

    public int[] durationsMs() {
        return durationsMs.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FlashSequence && colours.equals(((FlashSequence) o).colours)
                && Arrays.equals(durationsMs, ((FlashSequence) o).durationsMs);
    }

    @Override
    public int hashCode() {
        return 31 * colours.hashCode() + Arrays.hashCode(durationsMs);
    }
}
