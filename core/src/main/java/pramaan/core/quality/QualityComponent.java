/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.quality;

/** Parts of face-image quality (Bible Part 6; ISO/IEC 29794-5 style). */
public enum QualityComponent {
    SIZE,
    POSE,
    SHARPNESS,
    BRIGHTNESS,
    UNIFORMITY,
    BACKLIGHT,
    DETECTOR_CONFIDENCE
}
