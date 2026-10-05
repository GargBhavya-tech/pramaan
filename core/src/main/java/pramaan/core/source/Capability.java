/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

/** Optional device or station capabilities that gate cues and challenges (Bible Part 31.2). */
public enum Capability {
    IRIS_CAMERA,
    NIR_ILLUMINATOR_CONTROL,
    /** A screen or light that faces the subject; needed for flash, gaze and reflex challenges. */
    RESIDENT_DISPLAY,
    SECOND_CAMERA,
    FOCUS_CONTROL,
    SIGNED_STREAM,
    CAPTURE_BURST,
    SENSOR_TIMESTAMPS,
    /** Frames arrive without lossy compression; needed for rPPG (Bible Part 8.5). */
    UNCOMPRESSED_FRAMES,
    KNOWN_BACKDROP
}
