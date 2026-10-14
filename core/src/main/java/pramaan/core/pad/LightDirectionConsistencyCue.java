/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * Light-direction consistency, part B.2 of Bible Part 43 (Build Map #87): a replayed face lit from the left in a room
 * lit from the right is inconsistent.
 *
 * <p>The Bible fits a first-order spherical-harmonics shading model to a 3-D face built from landmarks. This is the
 * 2-D, first-order form of the same idea: fit a plane {@code L(x, y) = a x + b y + c} to the luminance of the face
 * crop and of the backdrop crop, take the unit direction of each gradient {@code (a, b)}, and compare them by cosine.
 * Both gradients point away from a lamp's side, so a live face and its room agree (cosine near +1) and a replay lit
 * from the opposite side does not (near -1). What it does not do: separate shading from albedo (a bearded or
 * shadowed face gives a gradient that is not light), or recover elevation. It is one cue among several and
 * carries {@link #PROVISIONAL_SLOPE}, not a corpus-trained scale.
 *
 * <p>Abstains ({@code NOT_APPLICABLE}) when either region is lit too evenly to have a direction — diffuse light is
 * not a reason to doubt anyone — and ({@code INPUT_TOO_SMALL}) on tiny crops or ({@code CAPABILITY_MISSING}) with
 * no backdrop.
 */
public final class LightDirectionConsistencyCue {

    public static final String NAME = "lightDirection";
    /** Relative luminance change across a region below which it counts as evenly lit. */
    public static final double MIN_GRADIENT = 0.05;
    public static final int MIN_PIXELS = 256;
    /** PROVISIONAL: scale of the cosine-to-probability logistic. */
    public static final double PROVISIONAL_SLOPE = 0.35;

    public CueResult evaluate(RgbImage faceCrop, RgbImage backdrop) {
        if (backdrop == null) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.CAPABILITY_MISSING);
        }
        if (faceCrop == null || (long) faceCrop.width() * faceCrop.height() < MIN_PIXELS
                || (long) backdrop.width() * backdrop.height() < MIN_PIXELS) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        double[] face = gradient(faceCrop);
        double[] room = gradient(backdrop);
        double mf = Math.hypot(face[0], face[1]);
        double mr = Math.hypot(room[0], room[1]);
        if (mf < MIN_GRADIENT || mr < MIN_GRADIENT) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.NOT_APPLICABLE);
        }
        double cos = (face[0] * room[0] + face[1] * room[1]) / (mf * mr);
        double p = 1 / (1 + Math.exp(-cos / PROVISIONAL_SLOPE));
        return CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), cos);
    }

    /**
     * Least-squares luminance plane over coordinates normalised to [-0.5, 0.5], as {@code (a, b)} divided by the mean
     * luminance: the relative luminance change across the whole region, per axis. Image y grows downward.
     */
    static double[] gradient(RgbImage img) {
        float[] luma = ImageOps.luma(img);
        int w = img.width();
        int h = img.height();
        double mean = 0;
        for (float v : luma) {
            mean += v / luma.length;
        }
        double sxl = 0;
        double syl = 0;
        double sxx = 0;
        double syy = 0;
        for (int y = 0; y < h; y++) {
            double ny = (y + 0.5) / h - 0.5;
            for (int x = 0; x < w; x++) {
                double nx = (x + 0.5) / w - 0.5;
                double l = luma[y * w + x] - mean;
                sxl += nx * l;
                syl += ny * l;
                sxx += nx * nx;
                syy += ny * ny;
            }
        }
        if (mean <= 1e-6) {
            return new double[] {0, 0};
        }
        return new double[] {sxl / sxx / mean, syl / syy / mean};
    }
}
