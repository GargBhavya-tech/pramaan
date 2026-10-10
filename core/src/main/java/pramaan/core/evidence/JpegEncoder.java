/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import java.io.IOException;
import pramaan.core.image.RgbImage;

/** Encodes a sample crop. Core has no image codecs; the desktop module supplies one (ImageIO). */
public interface JpegEncoder {

    byte[] encode(RgbImage image) throws IOException;
}
