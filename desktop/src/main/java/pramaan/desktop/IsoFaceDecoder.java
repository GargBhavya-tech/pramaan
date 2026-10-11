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
import pramaan.core.binding.IsoFaceRecord;
import pramaan.core.image.RgbImage;

/**
 * Decodes the image in a signed capture's ISO 19794-5 face record (Build Map #56). MOSIP devices return
 * JPEG 2000, which the JDK cannot read: {@code jai-imageio-jpeg2000} supplies the reader, at the same
 * version MOSIP's own biometrics-util uses (docs/licences.md).
 */
public final class IsoFaceDecoder {

    private IsoFaceDecoder() {
    }

    public static RgbImage decode(byte[] isoRecord) throws IOException {
        return decodeImage(IsoFaceRecord.parse(isoRecord).image());
    }

    /** The image inside a face record (JPEG, PNG or JPEG 2000) to pixels: the desktop {@code ImageDecoder}. */
    public static RgbImage decodeImage(byte[] encoded) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(encoded));
        if (img == null) {
            throw new IOException("no image reader for these bytes (JPEG, PNG and JPEG 2000 are supported)");
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
