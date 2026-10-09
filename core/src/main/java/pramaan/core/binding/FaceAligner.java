/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import pramaan.core.image.RgbImage;

/**
 * The 112x112 face crop SFace expects, made as OpenCV's {@code FaceRecognizerSF.alignCrop} makes it
 * (Build Map #56): the least-squares similarity transform taking the five YuNet landmarks onto the ArcFace
 * reference points, then a bilinear warp with a black border. Parity with OpenCV is checked in the
 * harness against {@code tools/sface_reference.py}.
 */
public final class FaceAligner {

    public static final int SIZE = 112;
    /** ArcFace reference: right eye, left eye, nose tip, right mouth corner, left mouth corner (YuNet's order). */
    static final double[] REFERENCE = {38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041};

    private FaceAligner() {
    }

    /** @param landmarks YuNet's five landmarks as x0, y0, ... x4, y4 in image pixels */
    public static RgbImage align(RgbImage image, float[] landmarks) {
        if (landmarks.length != 10) {
            throw new IllegalArgumentException("need 5 landmarks (10 values)");
        }
        double[] src = new double[10];
        for (int i = 0; i < 10; i++) {
            src[i] = landmarks[i];
        }
        double[] m = similarity(src, REFERENCE);
        double a = m[0];
        double b = m[1];
        double tx = m[2];
        double ty = m[3];
        double det = a * a + b * b;
        byte[] out = new byte[SIZE * SIZE * 3];
        for (int v = 0; v < SIZE; v++) {
            for (int u = 0; u < SIZE; u++) {
                double du = u - tx;
                double dv = v - ty;
                double x = (a * du + b * dv) / det;
                double y = (-b * du + a * dv) / det;
                for (int c = 0; c < 3; c++) {
                    out[(v * SIZE + u) * 3 + c] = (byte) Math.round(sample(image, x, y, c));
                }
            }
        }
        return RgbImage.of(SIZE, SIZE, out);
    }

    /** {a, b, tx, ty} minimising |[a -b; b a] src + t - dst|^2 (a similarity: rotation, uniform scale, shift). */
    static double[] similarity(double[] src, double[] dst) {
        int n = src.length / 2;
        double sx = 0;
        double sy = 0;
        double dx = 0;
        double dy = 0;
        for (int i = 0; i < n; i++) {
            sx += src[2 * i];
            sy += src[2 * i + 1];
            dx += dst[2 * i];
            dy += dst[2 * i + 1];
        }
        sx /= n;
        sy /= n;
        dx /= n;
        dy /= n;
        double num1 = 0;
        double num2 = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            double x = src[2 * i] - sx;
            double y = src[2 * i + 1] - sy;
            double u = dst[2 * i] - dx;
            double v = dst[2 * i + 1] - dy;
            num1 += x * u + y * v;
            num2 += x * v - y * u;
            den += x * x + y * y;
        }
        double a = num1 / den;
        double b = num2 / den;
        return new double[] {a, b, dx - (a * sx - b * sy), dy - (b * sx + a * sy)};
    }

    private static double sample(RgbImage img, double x, double y, int c) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        return (1 - fx) * (1 - fy) * px(img, x0, y0, c) + fx * (1 - fy) * px(img, x0 + 1, y0, c)
                + (1 - fx) * fy * px(img, x0, y0 + 1, c) + fx * fy * px(img, x0 + 1, y0 + 1, c);
    }

    private static int px(RgbImage img, int x, int y, int c) {
        return x < 0 || y < 0 || x >= img.width() || y >= img.height() ? 0 : img.get(x, y, c);
    }
}
