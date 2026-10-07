/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import pramaan.core.image.RgbImage;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameDecoder;
import pramaan.core.source.PixelFormat;

/** Desktop JPEG decoding with ImageIO. Lives outside core because javax.imageio does not exist on Android. */
public final class ImageIoFrameDecoder implements FrameDecoder {

    @Override
    public RgbImage decode(Frame frame) throws IOException {
        if (frame.format() != PixelFormat.JPEG) {
            throw new IOException("expected a JPEG frame, got " + frame.format());
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(frame.copyBytes()));
        if (img == null) {
            throw new IOException("frame " + frame.index() + " is not a decodable image");
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0; i < argb.length; i++) {
            rgb[3 * i] = (byte) (argb[i] >> 16);
            rgb[3 * i + 1] = (byte) (argb[i] >> 8);
            rgb[3 * i + 2] = (byte) argb[i];
        }
        return RgbImage.of(w, h, rgb);
    }
}
