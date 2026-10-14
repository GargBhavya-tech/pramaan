/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * Fingerprint of a centre's empty backdrop (Bible Part 43 A; Build Map #87): the colour histogram and a set of corner
 * keypoints with binary descriptors. Captured once at installation (and after any audited camera move) from the empty
 * scene, then compared with the <em>visible background</em> of each live frame.
 *
 * <p>The Bible names ORB keypoints; this module has no image-processing library, so the keypoints here are Harris
 * corners with 256-bit BRIEF descriptors on a smoothed patch — the same family (ORB is oriented FAST plus rotated
 * BRIEF) without the rotation, which a fixed registration-desk camera does not need. Matching is
 * nearest-neighbour with a ratio test.
 *
 * <p>Working resolution is {@value #W}x{@value #H}. An exclusion box (the resident, from the face box grown to a body
 * box by the caller) blanks that part of the frame: the histogram and keypoints use only pixels outside it. Real
 * person segmentation (the Bible suggests a selfie-segmentation model) would produce a tighter mask; the box is the
 * conservative stand-in, losing some background rather than ever counting the resident as scenery.
 */
public final class SceneFingerprint {

    static final int W = 160;
    static final int H = 120;
    private static final int BINS = 16;
    private static final int MAX_KEYPOINTS = 150;
    private static final int BORDER = 14;
    private static final int NMS_RADIUS = 5;
    private static final int DESCRIPTOR_BITS = 256;

    /** A corner and its descriptor; coordinates are in working-resolution pixels. */
    static final class Keypoint {
        final int x;
        final int y;
        final long[] bits;

        Keypoint(int x, int y, long[] bits) {
            this.x = x;
            this.y = y;
            this.bits = bits;
        }

        int hamming(Keypoint o) {
            int d = 0;
            for (int i = 0; i < bits.length; i++) {
                d += Long.bitCount(bits[i] ^ o.bits[i]);
            }
            return d;
        }
    }

    /** How a live frame's background compares with a stored fingerprint. */
    public static final class Comparison {
        /** Pearson correlation of the colour histograms, clamped to [0, 1]. */
        public final double histCorr;
        /** Matched keypoints over the fingerprint's keypoints that lie in the compared area. */
        public final double matchRatio;
        public final int matches;
        /** Median keypoint displacement in working-resolution pixels. */
        public final double medianShift;
        /** The matches moved together by a clear, consistent offset: the camera has been bumped. */
        public final boolean cameraMoved;
        /** Fraction of the frame that was visible background in both images. */
        public final double visibleFraction;

        Comparison(double histCorr, double matchRatio, int matches, double medianShift, boolean cameraMoved,
                double visibleFraction) {
            this.histCorr = histCorr;
            this.matchRatio = matchRatio;
            this.matches = matches;
            this.medianShift = medianShift;
            this.cameraMoved = cameraMoved;
            this.visibleFraction = visibleFraction;
        }

        /** Bible Part 43 A: {@code S_bg = alpha * HistCorr + (1 - alpha) * matches / keypoints}. */
        public double score(double alpha) {
            return alpha * histCorr + (1 - alpha) * matchRatio;
        }
    }

    private final double[] hist;
    private final boolean[] valid;
    private final List<Keypoint> keypoints;
    private final double visibleFraction;

    private SceneFingerprint(double[] hist, boolean[] valid, List<Keypoint> keypoints, double visibleFraction) {
        this.hist = hist;
        this.valid = valid;
        this.keypoints = keypoints;
        this.visibleFraction = visibleFraction;
    }

    /** Fingerprint of an entire empty scene. */
    public static SceneFingerprint capture(RgbImage scene) {
        return capture(scene, null);
    }

    /**
     * @param exclude {@code {x0, y0, x1, y1}} in {@code scene} pixel coordinates to leave out (the resident), or null
     */
    public static SceneFingerprint capture(RgbImage scene, int[] exclude) {
        RgbImage small = ImageOps.resize(scene, W, H);
        boolean[] valid = new boolean[W * H];
        Arrays.fill(valid, true);
        if (exclude != null) {
            int x0 = exclude[0] * W / scene.width();
            int y0 = exclude[1] * H / scene.height();
            int x1 = (exclude[2] * W + scene.width() - 1) / scene.width();
            int y1 = (exclude[3] * H + scene.height() - 1) / scene.height();
            for (int y = Math.max(0, y0); y < Math.min(H, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(W, x1); x++) {
                    valid[y * W + x] = false;
                }
            }
        }
        int count = 0;
        for (boolean v : valid) {
            count += v ? 1 : 0;
        }
        double[] hist = new double[3 * BINS];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (valid[y * W + x]) {
                    for (int c = 0; c < 3; c++) {
                        hist[c * BINS + small.get(x, y, c) * BINS / 256]++;
                    }
                }
            }
        }
        if (count > 0) {
            for (int i = 0; i < hist.length; i++) {
                hist[i] /= count;
            }
        }
        return new SceneFingerprint(hist, valid, keypoints(small, valid), (double) count / (W * H));
    }

    public int keypointCount() {
        return keypoints.size();
    }

    private static final int MAGIC = 0x50525346; // "PRSF"
    private static final int FORMAT = 1;

    /**
     * The fingerprint as stored at installation: a four-byte magic, a format number, the histogram, the visible-area
     * mask (bit-packed) and the keypoints. It holds colour statistics and corner descriptors of an <em>empty</em>
     * room, not a picture of it and not a person.
     */
    public byte[] toBytes() {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(hist.length);
            for (double h : hist) {
                out.writeDouble(h);
            }
            byte[] mask = new byte[(valid.length + 7) / 8];
            for (int i = 0; i < valid.length; i++) {
                if (valid[i]) {
                    mask[i / 8] |= (byte) (1 << (i % 8));
                }
            }
            out.writeInt(valid.length);
            out.write(mask);
            out.writeInt(keypoints.size());
            for (Keypoint k : keypoints) {
                out.writeShort(k.x);
                out.writeShort(k.y);
                for (long w : k.bits) {
                    out.writeLong(w);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot serialise a fingerprint", e);
        }
    }

    /** @throws IllegalArgumentException if the bytes are not a fingerprint of this format and size */
    public static SceneFingerprint fromBytes(byte[] data) {
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
                throw new IllegalArgumentException("not a scene fingerprint of format " + FORMAT);
            }
            double[] hist = new double[in.readInt()];
            if (hist.length != 3 * BINS) {
                throw new IllegalArgumentException("unexpected histogram size " + hist.length);
            }
            for (int i = 0; i < hist.length; i++) {
                hist[i] = in.readDouble();
            }
            int cells = in.readInt();
            if (cells != W * H) {
                throw new IllegalArgumentException("fingerprint was made at another working resolution");
            }
            byte[] mask = new byte[(cells + 7) / 8];
            in.readFully(mask);
            boolean[] valid = new boolean[cells];
            int visible = 0;
            for (int i = 0; i < cells; i++) {
                valid[i] = (mask[i / 8] >> (i % 8) & 1) != 0;
                visible += valid[i] ? 1 : 0;
            }
            int n = in.readInt();
            if (n < 0 || n > MAX_KEYPOINTS) {
                throw new IllegalArgumentException("unexpected keypoint count " + n);
            }
            List<Keypoint> keypoints = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int x = in.readUnsignedShort();
                int y = in.readUnsignedShort();
                if (x >= W || y >= H) {
                    throw new IllegalArgumentException("keypoint outside the working image");
                }
                long[] bits = new long[DESCRIPTOR_BITS / 64];
                for (int j = 0; j < bits.length; j++) {
                    bits[j] = in.readLong();
                }
                keypoints.add(new Keypoint(x, y, bits));
            }
            return new SceneFingerprint(hist, valid, keypoints, (double) visible / cells);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("truncated or unreadable fingerprint: " + e.getMessage(), e);
        }
    }

    public double visibleFraction() {
        return visibleFraction;
    }

    /** Compares this stored fingerprint with a live frame's (usually partly occluded) one. */
    public Comparison compare(SceneFingerprint live) {
        double corr = Math.max(0, pearson(hist, live.hist));
        List<Keypoint> inArea = new ArrayList<>();
        for (Keypoint k : keypoints) {
            if (live.valid[k.y * W + k.x]) {
                inArea.add(k);
            }
        }
        List<double[]> shifts = new ArrayList<>();
        for (Keypoint k : inArea) {
            int best = Integer.MAX_VALUE;
            int second = Integer.MAX_VALUE;
            Keypoint bestKp = null;
            for (Keypoint o : live.keypoints) {
                int d = k.hamming(o);
                if (d < best) {
                    second = best;
                    best = d;
                    bestKp = o;
                } else if (d < second) {
                    second = d;
                }
            }
            if (bestKp != null && best <= 56 && best < 0.8 * second) {
                shifts.add(new double[] {bestKp.x - k.x, bestKp.y - k.y});
            }
        }
        double ratio = inArea.isEmpty() ? 0 : (double) shifts.size() / inArea.size();
        double medianShift = 0;
        boolean moved = false;
        if (shifts.size() >= 8) {
            double mx = median(shifts, 0);
            double my = median(shifts, 1);
            medianShift = Math.hypot(mx, my);
            int agree = 0;
            for (double[] s : shifts) {
                agree += Math.hypot(s[0] - mx, s[1] - my) <= 2.0 ? 1 : 0;
            }
            // A bumped camera moves the whole scene together: most matches share one offset. A swapped room
            // produces few matches and no common offset.
            moved = medianShift >= 2.5 && agree >= 0.6 * shifts.size();
        }
        double common = 0;
        for (int i = 0; i < valid.length; i++) {
            common += valid[i] && live.valid[i] ? 1 : 0;
        }
        return new Comparison(corr, ratio, shifts.size(), medianShift, moved, common / valid.length);
    }

    private static double median(List<double[]> v, int axis) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = v.get(i)[axis];
        }
        Arrays.sort(a);
        return a[a.length / 2];
    }

    private static double pearson(double[] a, double[] b) {
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < a.length; i++) {
            ma += a[i] / a.length;
            mb += b[i] / b.length;
        }
        double sab = 0;
        double saa = 0;
        double sbb = 0;
        for (int i = 0; i < a.length; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += (a[i] - ma) * (a[i] - ma);
            sbb += (b[i] - mb) * (b[i] - mb);
        }
        return saa <= 0 || sbb <= 0 ? 0 : sab / Math.sqrt(saa * sbb);
    }

    // --- keypoints -------------------------------------------------------------------------------------------

    private static List<Keypoint> keypoints(RgbImage img, boolean[] valid) {
        float[] g = ImageOps.luma(img);
        float[] smooth = box3(g);
        double[] r = harris(g);
        // Strongest first, with a minimum spacing.
        Integer[] order = new Integer[W * H];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (p, q) -> Double.compare(r[q], r[p]));
        double floor = r[order[0]] * 1e-3;
        List<Keypoint> out = new ArrayList<>();
        for (int idx : order) {
            if (out.size() >= MAX_KEYPOINTS || r[idx] <= floor || r[idx] <= 0) {
                break;
            }
            int x = idx % W;
            int y = idx / W;
            if (x < BORDER || y < BORDER || x >= W - BORDER || y >= H - BORDER || !valid[idx]) {
                continue;
            }
            boolean crowded = false;
            for (Keypoint k : out) {
                if (Math.abs(k.x - x) <= NMS_RADIUS && Math.abs(k.y - y) <= NMS_RADIUS) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                out.add(new Keypoint(x, y, brief(smooth, x, y)));
            }
        }
        return out;
    }

    /** Harris corner response from Sobel gradients summed over a 5x5 window. */
    private static double[] harris(float[] g) {
        double[] ixx = new double[W * H];
        double[] iyy = new double[W * H];
        double[] ixy = new double[W * H];
        for (int y = 1; y < H - 1; y++) {
            for (int x = 1; x < W - 1; x++) {
                double gx = -g[(y - 1) * W + x - 1] + g[(y - 1) * W + x + 1] - 2 * g[y * W + x - 1]
                        + 2 * g[y * W + x + 1] - g[(y + 1) * W + x - 1] + g[(y + 1) * W + x + 1];
                double gy = -g[(y - 1) * W + x - 1] - 2 * g[(y - 1) * W + x] - g[(y - 1) * W + x + 1]
                        + g[(y + 1) * W + x - 1] + 2 * g[(y + 1) * W + x] + g[(y + 1) * W + x + 1];
                ixx[y * W + x] = gx * gx;
                iyy[y * W + x] = gy * gy;
                ixy[y * W + x] = gx * gy;
            }
        }
        double[] r = new double[W * H];
        for (int y = 3; y < H - 3; y++) {
            for (int x = 3; x < W - 3; x++) {
                double sxx = 0;
                double syy = 0;
                double sxy = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int i = (y + dy) * W + x + dx;
                        sxx += ixx[i];
                        syy += iyy[i];
                        sxy += ixy[i];
                    }
                }
                double det = sxx * syy - sxy * sxy;
                double trace = sxx + syy;
                r[y * W + x] = det - 0.04 * trace * trace;
            }
        }
        return r;
    }

    private static float[] box3(float[] g) {
        float[] out = g.clone();
        for (int y = 1; y < H - 1; y++) {
            for (int x = 1; x < W - 1; x++) {
                float s = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        s += g[(y + dy) * W + x + dx];
                    }
                }
                out[y * W + x] = s / 9;
            }
        }
        return out;
    }

    private static long[] brief(float[] smooth, int x, int y) {
        long[] bits = new long[DESCRIPTOR_BITS / 64];
        for (int i = 0; i < DESCRIPTOR_BITS; i++) {
            int x1 = x + offset(i, 0);
            int y1 = y + offset(i, 1);
            int x2 = x + offset(i, 2);
            int y2 = y + offset(i, 3);
            if (smooth[y1 * W + x1] < smooth[y2 * W + x2]) {
                bits[i / 64] |= 1L << (i % 64);
            }
        }
        return bits;
    }

    /** A fixed, well-spread test pattern in [-12, 12]: an integer hash of (pair, coordinate), not a secret. */
    private static int offset(int pair, int coord) {
        long h = (pair * 4L + coord + 1) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) Math.floorMod(h, 25L) - 12;
    }
}
