/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;
import pramaan.core.image.ImageDecoder;
import pramaan.core.image.RgbImage;

/**
 * JPEG and PNG to pixels with {@link BitmapFactory}. Android has no JPEG 2000 decoder: a device app running the
 * engine (Mode D) writes its own capture, so it wraps JPEG in the ISO record; a JPEG 2000 capture from elsewhere
 * fails to decode, and the identity lock then fails closed (no face in the capture).
 */
public final class BitmapImageDecoder implements ImageDecoder {

    @Override
    public RgbImage decode(byte[] encoded) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, o);
        if (b == null) {
            throw new IOException("not a JPEG or PNG image Android can decode");
        }
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            int[] argb = new int[w * h];
            b.getPixels(argb, 0, w, 0, 0, w, h);
            return Pixels.fromArgb(argb, w, h);
        } finally {
            b.recycle();
        }
    }
}
