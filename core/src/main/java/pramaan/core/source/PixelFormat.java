/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

/** How a frame's bytes are encoded. SBI streams deliver JPEG; decoding happens outside core (Android/desktop). */
public enum PixelFormat {
    JPEG,
    BGR24,
    RGB24,
    GRAY8
}
