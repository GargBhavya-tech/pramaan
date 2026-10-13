/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import java.io.IOException;
import pramaan.core.image.RgbImage;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameDecoder;
import pramaan.core.source.PixelFormat;

/** Camera frames delivered as JPEG, decoded as {@link BitmapImageDecoder} decodes any image. */
public final class BitmapFrameDecoder implements FrameDecoder {

    private final BitmapImageDecoder images = new BitmapImageDecoder();

    @Override
    public RgbImage decode(Frame frame) throws IOException {
        if (frame.format() != PixelFormat.JPEG) {
            throw new IOException("unsupported frame format " + frame.format());
        }
        return images.decode(frame.copyBytes());
    }
}
