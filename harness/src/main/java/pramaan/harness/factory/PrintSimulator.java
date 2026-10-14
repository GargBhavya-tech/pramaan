/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import pramaan.core.image.RgbImage;

/**
 * Print simulator (Bible Part 45, simulator 2; Build Map #89): a bona fide frame as seen when it is printed and the
 * print is held up to the camera.
 *
 * <ol>
 *   <li><b>Bend.</b> A horizontal bow, as a curved photograph has, resampled bilinearly.
 *   <li><b>Separation.</b> RGB to cyan, magenta, yellow and black (the usual {@code K = 1 - max(r, g, b)} form).
 *   <li><b>Halftone screening.</b> Each ink is a cosine screen at its own angle (C 15, M 75, Y 0, K 45 degrees) with a
 *       cell of {@code cellPx} pixels; a dot is printed where the ink amount exceeds the screen value, smoothed over
 *       a narrow edge. A mid-grey therefore carries a periodic pattern along a row of period {@code cellPx * sqrt(2)}
 *       for the 45-degree black screen — {@link #blackScreenPeriod(double)}.
 *   <li><b>Paper.</b> A warm white with a slow tooth texture, and gloss: a soft specular highlight.
 * </ol>
 *
 * <p>Deterministic in its parameters and seed. Real LPI maps to {@code cellPx} through the print's projected size;
 * 85-175 LPI at a phone-camera distance is roughly 3-8 pixels per cell.
 */
public final class PrintSimulator {

    /** Simulation settings. */
    public static final class Params {
        /** Halftone cell size in output pixels. */
        public double cellPx = 6;
        /** Edge softness of a printed dot, in screen-value units. */
        public double softness = 0.12;
        /** Paper white (R, G, B). */
        public double[] paper = {0.95, 0.93, 0.88};
        /** Amplitude of the paper tooth texture. */
        public double tooth = 0.02;
        /** Peak strength of the gloss highlight. */
        public double gloss = 0.15;
        /** Bow: 0 flat; 0.1 compresses the edges by about 10%. */
        public double bend = 0;
        public long seed = 1;
    }

    private static final double[] ANGLES_DEG = {15, 75, 0, 45}; // C, M, Y, K

    private PrintSimulator() {
    }

    /** Period along a row, in pixels, of the 45-degree black screen: {@code cellPx * sqrt(2)}. */
    public static double blackScreenPeriod(double cellPx) {
        return cellPx * Math.sqrt(2);
    }

    public static RgbImage render(RgbImage source, Params p) {
        int w = source.width();
        int h = source.height();
        double gx = (0.2 + 0.6 * SyntheticNoise.unit(7, 8, p.seed)) * w;
        double gy = (0.1 + 0.5 * SyntheticNoise.unit(9, 10, p.seed)) * h;
        double sigma = 0.22 * Math.min(w, h);
        byte[] out = new byte[w * h * 3];
        double[] rgb = new double[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double nx = (x - w / 2.0) / (w / 2.0);
                double sx = w / 2.0 + (x - w / 2.0) * (1 + p.bend * nx * nx);
                sample(source, sx, y, rgb);
                double k = 1 - Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
                double denom = Math.max(1e-6, 1 - k);
                double[] ink = {(1 - rgb[0] - k) / denom, (1 - rgb[1] - k) / denom, (1 - rgb[2] - k) / denom, k};
                double[] dot = new double[4];
                for (int i = 0; i < 4; i++) {
                    dot[i] = printed(ink[i], x, y, ANGLES_DEG[i], p);
                }
                double tooth = 1 + p.tooth * (SyntheticNoise.smooth(x, y, 2.5, p.seed) - 0.5) * 2;
                double gloss = p.gloss * Math.exp(-((x - gx) * (x - gx) + (y - gy) * (y - gy)) / (2 * sigma * sigma));
                double[] refl = {
                    p.paper[0] * (1 - dot[0]) * (1 - dot[3]),
                    p.paper[1] * (1 - dot[1]) * (1 - dot[3]),
                    p.paper[2] * (1 - dot[2]) * (1 - dot[3])};
                for (int c = 0; c < 3; c++) {
                    double v = refl[c] * tooth + gloss;
                    out[(y * w + x) * 3 + c] = (byte) Math.max(0, Math.min(255, Math.round(v * 255)));
                }
            }
        }
        return RgbImage.of(w, h, out);
    }

    /** 1 where the screen prints a dot for this ink amount, 0 where it does not, smooth across the edge. */
    private static double printed(double amount, int x, int y, double angleDeg, Params p) {
        double a = Math.toRadians(angleDeg);
        double u = x * Math.cos(a) + y * Math.sin(a);
        double v = -x * Math.sin(a) + y * Math.cos(a);
        double screen = (Math.cos(2 * Math.PI * u / p.cellPx) + Math.cos(2 * Math.PI * v / p.cellPx)) / 4 + 0.5;
        double t = (amount - screen) / p.softness;
        return Math.max(0, Math.min(1, 0.5 + t));
    }

    private static void sample(RgbImage img, double x, double y, double[] out) {
        double cx = Math.max(0, Math.min(img.width() - 1.001, x));
        double cy = Math.max(0, Math.min(img.height() - 1.001, y));
        int x0 = (int) cx;
        int y0 = (int) cy;
        double tx = cx - x0;
        double ty = cy - y0;
        for (int c = 0; c < 3; c++) {
            double top = img.get(x0, y0, c) * (1 - tx) + img.get(x0 + 1, y0, c) * tx;
            double bottom = img.get(x0, y0 + 1, c) * (1 - tx) + img.get(x0 + 1, y0 + 1, c) * tx;
            out[c] = (top * (1 - ty) + bottom * ty) / 255.0;
        }
    }
}
