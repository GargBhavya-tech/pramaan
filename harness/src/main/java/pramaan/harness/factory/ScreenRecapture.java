/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import pramaan.core.image.RgbImage;

/**
 * Screen-recapture simulator (Bible Part 45, simulator 1; Build Map #89): a bona fide frame as seen when someone
 * photographs a phone or monitor held up to the camera.
 *
 * <ol>
 *   <li><b>Display.</b> Each source pixel becomes a display pixel of {@code displayPitchPx} sensor pixels, lit as
 *       vertical R, G, B stripes (the other two stripes leak a little) with a dark gap between pixels.
 *   <li><b>Sensor.</b> The sensor integrates the display over each of its own pixels (an S x S area sample). With no
 *       optical pre-filter this aliases, and moiré <em>emerges</em> rather than being drawn: the display grid's
 *       frequency {@code f = 1 / displayPitchPx} (cycles per sensor pixel) folds to
 *       {@link #predictedBeat(double)} = |f - round(f)|, the Bible's {@code |1/p_d' - 1/p_s|} with the sensor pitch
 *       taken as one pixel and the nearest sampling harmonic.
 *   <li><b>Glass and gamut.</b> A cool shift (the screen's limited gamut and blue tint) and a soft specular
 *       highlight from the glass.
 *   <li><b>Refresh banding.</b> A display brightness ripple {@code 1 + m cos(2 pi f t)} integrated over a row's
 *       exposure gives amplitude {@code m |sinc(f E)|}, with rows offset by the line time. It vanishes when the
 *       exposure is a whole number of ripple periods — exactly when real cameras show no bands.
 * </ol>
 *
 * <p>Deterministic: the same source, parameters and seed give the same pixels. Synthetic attacks stretch training and
 * stress tests; headline APCER always comes from real presentations (Part 45).
 */
public final class ScreenRecapture {

    /** Simulation settings; the defaults describe a phone at close range. */
    public static final class Params {
        /** Display pixel pitch as projected onto the sensor, in sensor pixels. Moiré needs this near 1. */
        public double displayPitchPx = 1.08;
        /** Sub-samples per sensor pixel along each axis. */
        public int supersample = 6;
        /** Brightness of the two unlit stripes relative to the lit one. */
        public double leak = 0.12;
        /** Fraction of a display pixel that is the dark gap between pixels, on each axis. */
        public double gap = 0.08;
        /** Gamut / white-point shift: per-channel gain (red down, blue up). */
        public double[] gamut = {0.96, 0.99, 1.06};
        /** Peak strength of the glass highlight (0 disables it). */
        public double glare = 0.18;
        /** Display brightness ripple depth, 0 for none. */
        public double ripple = 0.10;
        public double rippleHz = 240;
        public double exposureMs = 2.0;
        public double lineTimeUs = 60;
        public long seed = 1;
    }

    private ScreenRecapture() {
    }

    /** The frequency (cycles per sensor pixel) that a display grid of this pitch aliases to. */
    public static double predictedBeat(double displayPitchPx) {
        double f = 1.0 / displayPitchPx;
        return Math.abs(f - Math.rint(f));
    }

    /** Banding amplitude as a fraction of brightness: {@code m |sinc(f E)|}. */
    public static double bandingAmplitude(Params p) {
        double x = p.rippleHz * p.exposureMs / 1000.0;
        double sinc = Math.abs(x) < 1e-9 ? 1 : Math.abs(Math.sin(Math.PI * x) / (Math.PI * x));
        return p.ripple * sinc;
    }

    public static RgbImage render(RgbImage source, Params p) {
        int w = (int) Math.round(source.width() * p.displayPitchPx);
        int h = (int) Math.round(source.height() * p.displayPitchPx);
        int s = p.supersample;
        double[] mean = maskMean(p);
        double gx = (0.25 + 0.5 * SyntheticNoise.unit(1, 2, p.seed)) * w;
        double gy = (0.15 + 0.4 * SyntheticNoise.unit(3, 4, p.seed)) * h;
        double sigma = 0.18 * Math.min(w, h);
        double band = bandingAmplitude(p);
        double phase0 = 2 * Math.PI * SyntheticNoise.unit(5, 6, p.seed);
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            double ripple = 1 + band * Math.cos(2 * Math.PI * p.rippleHz * (y * p.lineTimeUs * 1e-6) + phase0);
            for (int x = 0; x < w; x++) {
                double[] acc = new double[3];
                for (int sy = 0; sy < s; sy++) {
                    double v = (y + (sy + 0.5) / s) / p.displayPitchPx;
                    int iv = Math.min(source.height() - 1, (int) v);
                    double fv = v - iv;
                    for (int sx = 0; sx < s; sx++) {
                        double u = (x + (sx + 0.5) / s) / p.displayPitchPx;
                        int iu = Math.min(source.width() - 1, (int) u);
                        double fu = u - iu;
                        int stripe = Math.min(2, (int) (fu * 3));
                        double dark = fu > 1 - p.gap || fv > 1 - p.gap ? 0.1 : 1;
                        for (int c = 0; c < 3; c++) {
                            double lit = c == stripe ? 1 : p.leak;
                            acc[c] += source.get(iu, iv, c) / 255.0 * lit * dark;
                        }
                    }
                }
                double glare = p.glare * Math.exp(-((x - gx) * (x - gx) + (y - gy) * (y - gy)) / (2 * sigma * sigma));
                for (int c = 0; c < 3; c++) {
                    double v = acc[c] / (s * s) / mean[c] * p.gamut[c] * ripple + glare;
                    out[(y * w + x) * 3 + c] = (byte) Math.max(0, Math.min(255, Math.round(v * 255)));
                }
            }
        }
        return RgbImage.of(w, h, out);
    }

    /** Mean transmission of the stripe/gap mask per channel, so a flat source renders at its own brightness. */
    private static double[] maskMean(Params p) {
        double[] m = new double[3];
        int n = 60;
        for (int i = 0; i < n; i++) {
            double fu = (i + 0.5) / n;
            for (int j = 0; j < n; j++) {
                double fv = (j + 0.5) / n;
                int stripe = Math.min(2, (int) (fu * 3));
                double dark = fu > 1 - p.gap || fv > 1 - p.gap ? 0.1 : 1;
                for (int c = 0; c < 3; c++) {
                    m[c] += (c == stripe ? 1 : p.leak) * dark / (n * n);
                }
            }
        }
        return m;
    }
}
