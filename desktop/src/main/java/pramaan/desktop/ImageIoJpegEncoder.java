/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import pramaan.core.evidence.JpegEncoder;
import pramaan.core.image.RgbImage;

/**
 * JPEG for evidence sample crops (Build Map #61). Quality 0.75, measured on real MockMDS face crops: five come to
 * 22.0 KB encrypted and base64-encoded, inside the 25 KB budget with room for busier faces (0.85 gave 27.3 KB,
 * 0.80 gave 24.1 KB). At 112x112, re-scored after downscaling to 80x80, the loss is not the limiting factor.
 */
public final class ImageIoJpegEncoder implements JpegEncoder {

    public static final float QUALITY = 0.75f;

    @Override
    public byte[] encode(RgbImage image) throws IOException {
        BufferedImage img = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                img.setRGB(x, y, (image.get(x, y, 0) << 16) | (image.get(x, y, 1) << 8) | image.get(x, y, 2));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
