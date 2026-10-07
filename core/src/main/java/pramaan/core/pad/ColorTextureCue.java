/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * Colour-texture cue (Bible Part 8.1; Build Map #30): uniform-LBP histograms on the Y, Cb, Cr, H, S and V
 * channels of a 64x64 face crop, scored by a logistic model. Recaptured faces (prints, screens) distort
 * colour and micro-texture differently from skin, and this classical cue fails differently from the CNN.
 *
 * <p>It needs weights trained on the corpus (#65, trained on degraded crops too). Until then it
 * <strong>abstains</strong> ({@code UNTRAINED}) rather than voting with made-up numbers.
 */
public final class ColorTextureCue {

    public static final String NAME = "colorTexture";
    public static final int SIZE = 64;
    public static final int BINS = 59;
    public static final int FEATURES = 6 * BINS;

    private final double[] weights;
    private final double bias;

    /** @param weights logistic weights of length {@link #FEATURES}, or null when not trained yet */
    public ColorTextureCue(double[] weights, double bias) {
        if (weights != null && weights.length != FEATURES) {
            throw new IllegalArgumentException("need " + FEATURES + " weights");
        }
        this.weights = weights == null ? null : weights.clone();
        this.bias = bias;
    }

    public CueResult evaluate(RgbImage faceCrop) {
        if (weights == null) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.UNTRAINED);
        }
        double[] f = features(faceCrop);
        double z = bias;
        for (int i = 0; i < FEATURES; i++) {
            z += weights[i] * f[i];
        }
        double p = Math.max(1e-6, Math.min(1 - 1e-6, 1 / (1 + Math.exp(-z))));
        return CueResult.vote(NAME, p, z);
    }

    /** Concatenated, L1-normalised uniform-LBP histograms of Y, Cb, Cr, H, S, V. */
    public static double[] features(RgbImage faceCrop) {
        RgbImage img = ImageOps.resize(faceCrop, SIZE, SIZE);
        float[][] ch = new float[6][SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = y * SIZE + x;
                double r = img.get(x, y, 0);
                double g = img.get(x, y, 1);
                double b = img.get(x, y, 2);
                ch[0][i] = (float) (0.299 * r + 0.587 * g + 0.114 * b);
                ch[1][i] = (float) (128 - 0.168736 * r - 0.331264 * g + 0.5 * b);
                ch[2][i] = (float) (128 + 0.5 * r - 0.418688 * g - 0.081312 * b);
                double max = Math.max(r, Math.max(g, b));
                double min = Math.min(r, Math.min(g, b));
                double d = max - min;
                double h = d == 0 ? 0 : max == r ? ((g - b) / d + 6) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
                ch[3][i] = (float) (h * 255 / 6);
                ch[4][i] = (float) (max == 0 ? 0 : 255 * d / max);
                ch[5][i] = (float) max;
            }
        }
        double[] out = new double[FEATURES];
        for (int c = 0; c < 6; c++) {
            double[] hist = lbpHistogram(ch[c], SIZE, SIZE);
            System.arraycopy(hist, 0, out, c * BINS, BINS);
        }
        return out;
    }

    /** Uniform LBP(8,1): 58 uniform patterns get their own bin, everything else shares bin 58. L1-normalised. */
    static double[] lbpHistogram(float[] g, int w, int h) {
        double[] hist = new double[BINS];
        int[] dx = {-1, 0, 1, 1, 1, 0, -1, -1};
        int[] dy = {-1, -1, -1, 0, 1, 1, 1, 0};
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float c = g[y * w + x];
                int code = 0;
                for (int k = 0; k < 8; k++) {
                    if (g[(y + dy[k]) * w + x + dx[k]] >= c) {
                        code |= 1 << k;
                    }
                }
                hist[UNIFORM_BIN[code]]++;
                n++;
            }
        }
        for (int i = 0; i < BINS; i++) {
            hist[i] /= n;
        }
        return hist;
    }

    static final int[] UNIFORM_BIN = new int[256];

    static {
        int next = 0;
        for (int code = 0; code < 256; code++) {
            int transitions = 0;
            for (int k = 0; k < 8; k++) {
                if (((code >> k) & 1) != ((code >> ((k + 1) % 8)) & 1)) {
                    transitions++;
                }
            }
            UNIFORM_BIN[code] = transitions <= 2 ? next++ : 58;
        }
    }
}
