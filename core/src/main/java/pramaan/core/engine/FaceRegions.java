/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import pramaan.core.detect.FaceBox;
import pramaan.core.image.RgbImage;

/**
 * The three regions the flash cue reads (Bible Part 8.6): the skin of the face as a whole, the nose bridge and the
 * cheeks. They are placed by fixed proportions of the detector's face box — the same box on every frame — so the
 * comparison between frames is the comparison of one patch of face with itself. Landmark-placed regions would track
 * the face more closely but would add the landmarker's jitter to a signal that is a fraction of one grey level.
 *
 * <pre>skin    x 20-80 %, y 25-85 % of the box
 * nose    x 42-58 %, y 40-65 %
 * cheeks  x 12-32 % and 68-88 %, y 50-75 %   (their pooled mean)</pre>
 */
public final class FaceRegions {

    private FaceRegions() {
    }

    /** {@code {skin, nose, cheeks}}, each a mean RGB in 0-255. */
    public static double[][] sample(RgbImage image, FaceBox face) {
        double[] skin = mean(image, face, 0.20, 0.25, 0.80, 0.85);
        double[] nose = mean(image, face, 0.42, 0.40, 0.58, 0.65);
        double[] left = mean(image, face, 0.12, 0.50, 0.32, 0.75);
        double[] right = mean(image, face, 0.68, 0.50, 0.88, 0.75);
        double[] cheeks = {(left[0] + right[0]) / 2, (left[1] + right[1]) / 2, (left[2] + right[2]) / 2};
        return new double[][] {skin, nose, cheeks};
    }

    private static double[] mean(RgbImage img, FaceBox f, double fx0, double fy0, double fx1, double fy1) {
        int x0 = clamp(Math.round(f.x() + fx0 * f.width()), img.width() - 1);
        int x1 = clamp(Math.round(f.x() + fx1 * f.width()), img.width() - 1);
        int y0 = clamp(Math.round(f.y() + fy0 * f.height()), img.height() - 1);
        int y1 = clamp(Math.round(f.y() + fy1 * f.height()), img.height() - 1);
        double[] sum = new double[3];
        long n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int c = 0; c < 3; c++) {
                    sum[c] += img.get(x, y, c);
                }
                n++;
            }
        }
        if (n == 0) {
            return new double[] {0, 0, 0};
        }
        return new double[] {sum[0] / n, sum[1] / n, sum[2] / n};
    }

    private static int clamp(long v, int max) {
        return (int) Math.max(0, Math.min(max, v));
    }
}
