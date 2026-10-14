/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import pramaan.core.challenge.FlashColour;

/**
 * A light that faces the resident (Bible Part 8.6): a second monitor, a ring light or the device's own illuminator.
 * Having one is the {@link Capability#RESIDENT_DISPLAY} capability; the engine never assumes it. The engine calls
 * {@link #show} and {@link #off} from its frame loop, on schedule, and always ends an attempt with {@link #off}.
 */
public interface FlashLight {

    /** Lights the whole light with this colour (nominal linear RGB from the palette). */
    void show(FlashColour colour);

    /** Turns it off. Must be safe to call when it is already off, and from any thread. */
    void off();
}
