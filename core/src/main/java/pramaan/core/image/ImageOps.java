/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.image;

/**
 * The few image operations the engine needs, in plain Java so the same code runs on desktop and Android.
 * Resizing matches OpenCV's INTER_LINEAR (pixel-centre alignment), because the models were trained on
 * images resized that way.
 */
public final class ImageOps {

    private ImageOps() {
    }

    /** Bilinear resize with OpenCV INTER_LINEAR geometry: src = (dst + 0.5) * scale - 0.5, clamped. */
    public static RgbImage resize(RgbImage src, int width, int height) {
        byte[] out = new byte[width * height * 3];
        double sx = (double) src.width() / width;
        double sy = (double) src.height() / height;
        for (int y = 0; y < height; y++) {
            double fy = Math.max(0, (y + 0.5) * sy - 0.5);
            int y0 = Math.min((int) fy, src.height() - 1);
            int y1 = Math.min(y0 + 1, src.height() - 1);
            double wy = fy - y0;
            for (int x = 0; x < width; x++) {
                double fx = Math.max(0, (x + 0.5) * sx - 0.5);
                int x0 = Math.min((int) fx, src.width() - 1);
                int x1 = Math.min(x0 + 1, src.width() - 1);
                double wx = fx - x0;
                for (int c = 0; c < 3; c++) {
                    double top = src.get(x0, y0, c) * (1 - wx) + src.get(x1, y0, c) * wx;
                    double bottom = src.get(x0, y1, c) * (1 - wx) + src.get(x1, y1, c) * wx;
                    out[(y * width + x) * 3 + c] = (byte) Math.min(255, (int) Math.round(top * (1 - wy) + bottom * wy));
                }
            }
        }
        return RgbImage.of(width, height, out);
    }

    /** Crops the inclusive rectangle [x0..x1] x [y0..y1], clamped to the image. */
    public static RgbImage crop(RgbImage src, int x0, int y0, int x1, int y1) {
        int left = clamp(Math.min(x0, x1), 0, src.width() - 1);
        int right = clamp(Math.max(x0, x1), 0, src.width() - 1);
        int top = clamp(Math.min(y0, y1), 0, src.height() - 1);
        int bottom = clamp(Math.max(y0, y1), 0, src.height() - 1);
        int w = right - left + 1;
        int h = bottom - top + 1;
        byte[] out = new byte[w * h * 3];
        byte[] raw = src.raw();
        for (int y = 0; y < h; y++) {
            System.arraycopy(raw, ((top + y) * src.width() + left) * 3, out, y * w * 3, w * 3);
        }
        return RgbImage.of(w, h, out);
    }

    /** CHW float tensor in BGR order with raw 0-255 values (YuNet, MiniFASNet). */
    public static float[] toChwBgr(RgbImage img) {
        int plane = img.width() * img.height();
        float[] out = new float[plane * 3];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                int i = y * img.width() + x;
                out[i] = img.get(x, y, 2);
                out[plane + i] = img.get(x, y, 1);
                out[2 * plane + i] = img.get(x, y, 0);
            }
        }
        return out;
    }

    /** BT.601 luma, 0-255. */
    public static float[] luma(RgbImage img) {
        float[] out = new float[img.width() * img.height()];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                out[y * img.width() + x] = (float) (0.299 * img.get(x, y, 0) + 0.587 * img.get(x, y, 1) + 0.114 * img.get(x, y, 2));
            }
        }
        return out;
    }

    /** Variance of the 4-neighbour Laplacian over interior pixels: collapses when the image is blurred. */
    public static double laplacianVariance(float[] gray, int width, int height) {
        if (width < 3 || height < 3) {
            return 0;
        }
        double sum = 0;
        double sumSq = 0;
        long n = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double v = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4.0 * gray[i];
                sum += v;
                sumSq += v * v;
                n++;
            }
        }
        double mean = sum / n;
        return sumSq / n - mean * mean;
    }

    /** Mean of a gray image over columns [x0, x1). */
    public static double meanColumns(float[] gray, int width, int height, int x0, int x1) {
        double sum = 0;
        long n = 0;
        for (int y = 0; y < height; y++) {
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
                sum += gray[y * width + x];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
