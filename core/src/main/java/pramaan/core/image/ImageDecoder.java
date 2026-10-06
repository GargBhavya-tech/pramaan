/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.image;

import java.io.IOException;

/**
 * Encoded image bytes (JPEG, PNG; JPEG 2000 where the platform can) to pixels. Each platform supplies its own —
 * ImageIO on desktop, {@code BitmapFactory} on Android — and core stays free of both (Build Map #1, #79).
 */
public interface ImageDecoder {

    RgbImage decode(byte[] encoded) throws IOException;
}
