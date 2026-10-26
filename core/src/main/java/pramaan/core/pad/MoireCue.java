/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * Moiré / frequency cue (Bible Part 8.2; Build Map #31). Filming a screen makes the screen's pixel grid
 * interfere with the sensor's, leaving sharp off-axis peaks in the spectrum. Peakiness = max / median
 * magnitude over the annulus r1 <= |f| <= r2 of a 128x128 window.
 *
 * <p>JPEG compression puts peaks in every image, screen or not: its 8x8 blocks leave energy on the lattice
 * of frequencies that are multiples of N/8 on both axes (and on the axes themselves). So the window is
 * taken at <strong>native pixel resolution, never resized</strong> — resizing would move that lattice to an
 * unknown place — and the lattice and axis bins are excluded. A face too small for a native 128-pixel
 * window abstains: moiré needs resolution to be seen at all.
 *
 * <p>The limit is PROVISIONAL (from the Bible's example ranges: bona fide ~3-6, recaptured ~15-40) and
 * must be re-derived from our own corpus (#65), because peakiness is device-specific.
 */
public final class MoireCue {

    public static final String NAME = "moire";
    public static final int N = 128;
    /** PROVISIONAL: peakiness at which live and spoof are equally likely, when no camera has been calibrated. */
    public static final double PROVISIONAL_THRESHOLD = pramaan.core.policy.MoireCalibration.PROVISIONAL_THRESHOLD;
    private final double threshold;
    private final double slope;
    private final boolean votes;

    /** The provisional calibration. A running engine takes its camera's calibration from the operating-point table instead. */
    public MoireCue() {
        this(PROVISIONAL_THRESHOLD, pramaan.core.policy.MoireCalibration.PROVISIONAL_SLOPE);
    }

    /**
     * @param threshold the peakiness at which a live face and a screen are equally likely
     * @param slope how sharply the vote moves around it
     */
    public MoireCue(double threshold, double slope) {
        this(threshold, slope, true);
    }

    private MoireCue(double threshold, double slope, boolean votes) {
        this.threshold = threshold;
        this.slope = slope;
        this.votes = votes;
    }

    /**
     * The cue as the operating-point table calibrates it for the connected camera. When the table says "abstain" the cue measures
     * nothing and abstains on every frame: on a camera where no measurement has shown that moire separates a live face from a screen,
     * a vote either way would be a guess, and a confident wrong vote rejects live faces.
     */
    public MoireCue(pramaan.core.policy.MoireCalibration calibration) {
        this(calibration.peakinessThreshold(), calibration.slope(), calibration.votes());
    }

    private static final int R1 = 12;
    private static final int R2 = 60;
    private static final int AXIS_BAND = 2;
    private static final int JPEG_BLOCK = 8;
    private static final int LATTICE_BAND = 1;

    /** @param nativeFaceCrop the face region cut from the decoded frame without any rescaling */
    public CueResult evaluate(RgbImage nativeFaceCrop) {
        if (!votes) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.UNTRAINED);
        }
        if (nativeFaceCrop.width() < N || nativeFaceCrop.height() < N) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        int x0 = (nativeFaceCrop.width() - N) / 2;
        int y0 = (nativeFaceCrop.height() - N) / 2;
        double peakiness = peakiness(ImageOps.luma(ImageOps.crop(nativeFaceCrop, x0, y0, x0 + N - 1, y0 + N - 1)));
        double p = 1 / (1 + Math.exp(slope * (peakiness - threshold)));
        return CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), peakiness);
    }

    /** True for frequencies near a multiple of N/8 — where 8x8 JPEG blocking leaves its energy. */
    private static boolean nearBlockLattice(int f) {
        int period = N / JPEG_BLOCK;
        int m = Math.floorMod(f, period);
        return m <= LATTICE_BAND || m >= period - LATTICE_BAND;
    }

    /** Max over median of the magnitude spectrum in the annulus, axis bands and JPEG lattice excluded. */
    static double peakiness(float[] gray) {
        double mean = 0;
        for (float v : gray) {
            mean += v;
        }
        mean /= gray.length;
        double[] re = new double[N * N];
        double[] im = new double[N * N];
        for (int i = 0; i < gray.length; i++) {
            int x = i % N;
            int y = i / N;
            // Periodic Hann (divide by N, not N-1): an on-bin component, which the 8-pixel JPEG grid is in a
            // 128-pixel window, then spreads to exactly +-1 bin, so the exclusion bands contain it completely.
            double hann = 0.25 * (1 - Math.cos(2 * Math.PI * x / N)) * (1 - Math.cos(2 * Math.PI * y / N));
            re[i] = (gray[i] - mean) * hann;
        }
        fft2(re, im);
        java.util.List<Double> mags = new java.util.ArrayList<>();
        double max = 0;
        for (int v = 0; v < N; v++) {
            for (int u = 0; u < N; u++) {
                int fu = u <= N / 2 ? u : u - N;
                int fv = v <= N / 2 ? v : v - N;
                double r = Math.hypot(fu, fv);
                if (r < R1 || r > R2 || Math.abs(fu) <= AXIS_BAND || Math.abs(fv) <= AXIS_BAND
                        || (nearBlockLattice(fu) && nearBlockLattice(fv))) {
                    continue;
                }
                double m = Math.hypot(re[v * N + u], im[v * N + u]);
                mags.add(m);
                max = Math.max(max, m);
            }
        }
        java.util.Collections.sort(mags);
        double median = mags.get(mags.size() / 2);
        return median <= 1e-9 ? 0 : max / median;
    }

    /** In-place 2-D FFT (radix-2, rows then columns). */
    static void fft2(double[] re, double[] im) {
        double[] r = new double[N];
        double[] i = new double[N];
        for (int y = 0; y < N; y++) {
            System.arraycopy(re, y * N, r, 0, N);
            System.arraycopy(im, y * N, i, 0, N);
            fft(r, i);
            System.arraycopy(r, 0, re, y * N, N);
            System.arraycopy(i, 0, im, y * N, N);
        }
        for (int x = 0; x < N; x++) {
            for (int y = 0; y < N; y++) {
                r[y] = re[y * N + x];
                i[y] = im[y * N + x];
            }
            fft(r, i);
            for (int y = 0; y < N; y++) {
                re[y * N + x] = r[y];
                im[y * N + x] = i[y];
            }
        }
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        int bits = Integer.numberOfTrailingZeros(n);
        for (int j = 1; j < n; j++) {
            int rev = Integer.reverse(j) >>> (32 - bits);
            if (j < rev) {
                double t = re[j];
                re[j] = re[rev];
                re[rev] = t;
                t = im[j];
                im[j] = im[rev];
                im[rev] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            for (int s = 0; s < n; s += len) {
                for (int k = 0; k < len / 2; k++) {
                    double wr = Math.cos(ang * k);
                    double wi = Math.sin(ang * k);
                    int a = s + k;
                    int b = a + len / 2;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}
