/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

/**
 * An iris camera with a controllable visible stimulus (Bible Part 32): the {@link Capability#IRIS_CAMERA} capability
 * together with a light. It measures the pupil; the engine decides when to flash, at random, and judges the response.
 */
public interface PupilSource {

    /** Fires one short visible flash now. */
    void stimulate();

    /**
     * Pupil radii measured between two instants, as rows of {@code {time in epoch milliseconds, radius in pixels}},
     * oldest first. The radius is NaN where the pupil could not be segmented (a blink). The clock is the one the
     * primary frames' {@code hostArrival} uses, so flash and measurement times are comparable.
     */
    double[][] radii(long fromMillis, long toMillis);
}
