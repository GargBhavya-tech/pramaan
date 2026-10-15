/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.experiment;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Cuts a stream of back-to-back JPEGs (what {@code ffmpeg -f image2pipe -c:v mjpeg} writes) into single images. A JPEG
 * starts with the bytes {@code FF D8} and ends with {@code FF D9}; inside the entropy-coded data every {@code FF} is
 * followed by {@code 00} or a restart marker, so {@code FF D9} only ever ends a picture. The caller stamps each image with
 * the host clock the moment it is returned, which is how the experiment pairs frames with the colour on screen.
 */
public final class JpegStreamSplitter {

    private final InputStream in;

    public JpegStreamSplitter(InputStream in) {
        this.in = new BufferedInputStream(in, 1 << 16);
    }

    /** @return the next complete JPEG, or null at the end of the stream (a truncated last picture is dropped) */
    public byte[] next() throws IOException {
        int prev = -1;
        int b;
        // find the start of image
        while ((b = in.read()) != -1) {
            if (prev == 0xFF && b == 0xD8) {
                break;
            }
            prev = b;
        }
        if (b == -1) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 15);
        out.write(0xFF);
        out.write(0xD8);
        prev = -1;
        while ((b = in.read()) != -1) {
            out.write(b);
            if (prev == 0xFF && b == 0xD9) {
                return out.toByteArray();
            }
            prev = b;
        }
        return null;
    }
}
