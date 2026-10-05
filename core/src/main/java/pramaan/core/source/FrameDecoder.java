/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.io.IOException;
import pramaan.core.image.RgbImage;

/** Turns an encoded frame into pixels. Platform-specific (ImageIO on desktop, BitmapFactory on Android). */
public interface FrameDecoder {

    RgbImage decode(Frame frame) throws IOException;
}
