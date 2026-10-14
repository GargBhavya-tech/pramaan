/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.Arrays;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * White-point consistency, part B.1 of Bible Part 43 (Build Map #87): a screen held up to the camera emits its own
 * light — often bluish, ~6500 K — while the room is lit differently, so the illuminant colour on the resident's
 * face should agree with the illuminant colour on the room around them.
 *
 * <p>Estimates the illuminant's rg-chromaticity by the grey-world assumption (mean R, G, B, normalised) — on the
 * backdrop as a whole, and on the face crop's brightest pixels (its specular highlight, where skin looks most like
 * a mirror of the light source):
 *
 * <pre>r = R / (R + G + B),  g = G / (R + G + B),  Δ = ||(r, g)_face - (r, g)_backdrop||</pre>
 *
 * <p><strong>Not built</strong> (Bible Part 43): the scene fingerprint itself (part A — ORB keypoints, an
 * installation-time empty-room capture, person segmentation to find "the visible background") and light-direction
 * consistency (part B.2 — a spherical-harmonics shading fit). This cue takes the backdrop region as a parameter;
 * supplying one that is actually the room's background, isolated from the resident, is the caller's job.
 * {@link #PROVISIONAL_THRESHOLD} is the Bible's own worked example, not a corpus-measured value (#65).
 */
public final class WhitePointConsistencyCue {

    public static final String NAME = "whitePoint";
    /** PROVISIONAL (Bible Part 43 worked example): Δ = 0.01 passes, Δ = 0.09 is suspicious. */
    public static final double PROVISIONAL_THRESHOLD = 0.04;
    /** The brightest this fraction of the face crop's pixels stand in for its specular highlight. */
    public static final double HIGHLIGHT_FRACTION = 0.05;
    public static final int MIN_PIXELS = 256;
    private static final double SLOPE = 40;

    /**
     * @param backdrop a crop of the room's visible background, isolated from the resident; null abstains
     *     ({@code CAPABILITY_MISSING} — Bible: "no backdrop fingerprint")
     * @param faceCrop the resident's face crop, at least {@value #MIN_PIXELS} pixels
     */
    public CueResult evaluate(RgbImage backdrop, RgbImage faceCrop) {
        if (backdrop == null || (long) backdrop.width() * backdrop.height() < MIN_PIXELS) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.CAPABILITY_MISSING);
        }
        if (faceCrop == null || (long) faceCrop.width() * faceCrop.height() < MIN_PIXELS) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        double[] bg = chromaticity(backdrop);
        double[] fh = highlightChromaticity(faceCrop);
        double delta = Math.hypot(fh[0] - bg[0], fh[1] - bg[1]);
        double p = 1 / (1 + Math.exp(SLOPE * (delta - PROVISIONAL_THRESHOLD)));
        return CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), delta);
    }

    /**
     * As above, for a whole frame with the people marked: the room's chromaticity is taken over the pixels that are
     * not a person, wherever they are, instead of from one margin of the frame.
     */
    public CueResult evaluate(RgbImage frame, PersonMask people, RgbImage faceCrop) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                if (!people.personAtFrame(x, y, frame.width(), frame.height())) {
                    r += frame.get(x, y, 0);
                    g += frame.get(x, y, 1);
                    b += frame.get(x, y, 2);
                    n++;
                }
            }
        }
        if (n < MIN_PIXELS) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.CAPABILITY_MISSING);
        }
        if (faceCrop == null || (long) faceCrop.width() * faceCrop.height() < MIN_PIXELS) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        double[] bg = rg(r, g, b);
        double[] fh = highlightChromaticity(faceCrop);
        double delta = Math.hypot(fh[0] - bg[0], fh[1] - bg[1]);
        double p = 1 / (1 + Math.exp(SLOPE * (delta - PROVISIONAL_THRESHOLD)));
        return CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), delta);
    }

    /** Grey-world rg-chromaticity of every pixel in the image. */
    static double[] chromaticity(RgbImage img) {
        long r = 0;
        long g = 0;
        long b = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                r += img.get(x, y, 0);
                g += img.get(x, y, 1);
                b += img.get(x, y, 2);
            }
        }
        return rg(r, g, b);
    }

    /** rg-chromaticity of the crop's brightest {@link #HIGHLIGHT_FRACTION} of pixels by luma. */
    static double[] highlightChromaticity(RgbImage img) {
        float[] luma = ImageOps.luma(img);
        float[] sorted = luma.clone();
        Arrays.sort(sorted);
        int count = Math.max(1, (int) (sorted.length * HIGHLIGHT_FRACTION));
        float threshold = sorted[sorted.length - count];
        long r = 0;
        long g = 0;
        long b = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                if (luma[y * img.width() + x] >= threshold) {
                    r += img.get(x, y, 0);
                    g += img.get(x, y, 1);
                    b += img.get(x, y, 2);
                }
            }
        }
        return rg(r, g, b);
    }

    private static double[] rg(long r, long g, long b) {
        double sum = r + g + b;
        return sum <= 0 ? new double[] {0, 0} : new double[] {r / sum, g / sum};
    }
}
