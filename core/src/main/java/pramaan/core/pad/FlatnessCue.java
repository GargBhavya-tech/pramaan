/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

/**
 * Planar-homography flatness cue (Bible Part 8.3; Build Map #32). A print or screen is a plane: its
 * landmarks in two views are related by one 3x3 homography. A real face is not — the nose moves against
 * the cheeks. Residual e = mean |p1 - H p0| / IOD after a least-squares (normalised DLT) fit.
 *
 * <p>Abstains unless the head turned at least 10 degrees between the two views: with no motion the
 * residual is meaningless. Honest limit: a <em>video</em> of a real face shows real parallax, so this cue
 * does not catch replays (moiré, flash and challenges do). Threshold 0.012 is PROVISIONAL.
 */
public final class FlatnessCue {

    public static final String NAME = "flatness";
    public static final double MIN_YAW_CHANGE_DEG = 10;
    /** PROVISIONAL residual separating planar (below) from 3-D (above). */
    public static final double PROVISIONAL_THRESHOLD = 0.012;

    /**
     * @param p0 frontal landmarks, x0,y0,x1,y1,... (at least 8 points)
     * @param p1 the same landmarks after the turn
     */
    public CueResult evaluate(double[] p0, double[] p1, double yawChangeDeg, double iod) {
        if (Math.abs(yawChangeDeg) < MIN_YAW_CHANGE_DEG) {
            return CueResult.abstain(NAME, CueResult.AbstainReason.NOT_ENOUGH_MOTION);
        }
        double e = residual(p0, p1) / iod;
        double p = 1 / (1 + Math.exp(-(e - PROVISIONAL_THRESHOLD) / 0.003));
        return CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), e);
    }

    /** Mean reprojection error (pixels) of the best homography mapping p0 to p1. */
    static double residual(double[] p0, double[] p1) {
        int n = p0.length / 2;
        if (n < 5 || p1.length != p0.length) {
            throw new IllegalArgumentException("need at least 5 matching points");
        }
        double[] t0 = normaliser(p0);
        double[] t1 = normaliser(p1);
        double[][] a = new double[8][8];
        double[] b = new double[8];
        for (int i = 0; i < n; i++) {
            double x = (p0[2 * i] - t0[0]) * t0[2];
            double y = (p0[2 * i + 1] - t0[1]) * t0[2];
            double u = (p1[2 * i] - t1[0]) * t1[2];
            double v = (p1[2 * i + 1] - t1[1]) * t1[2];
            double[][] rows = {{x, y, 1, 0, 0, 0, -u * x, -u * y}, {0, 0, 0, x, y, 1, -v * x, -v * y}};
            double[] rhs = {u, v};
            for (int r = 0; r < 2; r++) {
                for (int j = 0; j < 8; j++) {
                    b[j] += rows[r][j] * rhs[r];
                    for (int k = 0; k < 8; k++) {
                        a[j][k] += rows[r][j] * rows[r][k];
                    }
                }
            }
        }
        double[] h = solve(a, b);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double x = (p0[2 * i] - t0[0]) * t0[2];
            double y = (p0[2 * i + 1] - t0[1]) * t0[2];
            double w = h[6] * x + h[7] * y + 1;
            double u = (h[0] * x + h[1] * y + h[2]) / w / t1[2] + t1[0];
            double v = (h[3] * x + h[4] * y + h[5]) / w / t1[2] + t1[1];
            sum += Math.hypot(u - p1[2 * i], v - p1[2 * i + 1]);
        }
        return sum / n;
    }

    /** Hartley normalisation: centroid and scale so the mean distance is sqrt(2). */
    private static double[] normaliser(double[] p) {
        int n = p.length / 2;
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < n; i++) {
            cx += p[2 * i];
            cy += p[2 * i + 1];
        }
        cx /= n;
        cy /= n;
        double d = 0;
        for (int i = 0; i < n; i++) {
            d += Math.hypot(p[2 * i] - cx, p[2 * i + 1] - cy);
        }
        d /= n;
        return new double[] {cx, cy, d == 0 ? 1 : Math.sqrt(2) / d};
    }

    /** Gaussian elimination with partial pivoting. */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
                    piv = r;
                }
            }
            double[] tr = a[c];
            a[c] = a[piv];
            a[piv] = tr;
            double tb = b[c];
            b[c] = b[piv];
            b[piv] = tb;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < n; k++) {
                    a[r][k] -= f * a[c][k];
                }
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) {
                s -= a[r][k] * x[k];
            }
            x[r] = s / a[r][r];
        }
        return x;
    }
}
