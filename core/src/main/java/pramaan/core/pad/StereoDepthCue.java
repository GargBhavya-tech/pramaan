/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

/**
 * Second-camera stereo depth cue (Bible Part 38; Build Map #86). Landmarks seen by a rectified pair are triangulated
 * to 3-D ({@code Z = f b / d}), a plane is fitted to them by least squares, and two numbers come out:
 *
 * <ul>
 *   <li><b>nose relief</b> — mean cheek depth minus nose-tip depth, in mm. A real face: about 20-30 mm. A print or a
 *       screen: 1-2 mm. A curved print has a non-zero plane residual but still no nose peak, so relief catches it.
 *   <li><b>plane residual</b> — RMS distance of the landmarks from the fitted plane, in mm.
 * </ul>
 *
 * <p><b>Injection consistency.</b> In a rectified pair the same landmark has the same row in both images. Injecting
 * into one camera leaves the other showing the real scene, so landmarks stop agreeing and the RMS row difference
 * (a reprojection-error proxy) rises. Above {@link #MAX_ROW_ERROR_PX} the pair is inconsistent and the vote is low.
 *
 * <p>Pairing is strict: frames more than {@link #MAX_PAIR_SKEW_MS} apart are not a stereo pair, and head motion
 * between them would inflate every error above, so the cue abstains ({@code NOT_APPLICABLE}). With no calibrated
 * second camera it abstains ({@code CAPABILITY_MISSING}). Thresholds are the Bible's worked example, not corpus values.
 */
public final class StereoDepthCue {

    public static final String NAME = "stereo";
    /** Bible Part 38: frames are paired only when their timestamps are within 20 ms. */
    public static final double MAX_PAIR_SKEW_MS = 20;
    /** PROVISIONAL: nose relief (mm) at which the vote crosses 0.5; a real face is 20-30, a print 1-2. */
    public static final double PROVISIONAL_RELIEF_MM = 10;
    private static final double RELIEF_SLOPE_MM = 2.5;
    /** PROVISIONAL: RMS row disagreement above which the two views cannot be one scene. */
    public static final double MAX_ROW_ERROR_PX = 1.5;
    public static final int MIN_LANDMARKS = 8;

    private final int noseTip;
    private final int[] cheeks;

    /** @param noseTip index of the nose-tip landmark; @param cheeks indices of the cheek landmarks */
    public StereoDepthCue(int noseTip, int... cheeks) {
        if (cheeks.length == 0) {
            throw new IllegalArgumentException("need at least one cheek landmark");
        }
        this.noseTip = noseTip;
        this.cheeks = cheeks.clone();
    }

    /** Everything measured, for diagnostics and the evidence record. */
    public static final class Analysis {
        public final CueResult result;
        public final double noseReliefMm;
        public final double planeResidualMm;
        public final double rowErrorPx;

        Analysis(CueResult result, double noseReliefMm, double planeResidualMm, double rowErrorPx) {
            this.result = result;
            this.noseReliefMm = noseReliefMm;
            this.planeResidualMm = planeResidualMm;
            this.rowErrorPx = rowErrorPx;
        }
    }

    public CueResult evaluate(StereoCalibration calibration, double[] left, double[] right, double leftTimeMs,
            double rightTimeMs) {
        return analyse(calibration, left, right, leftTimeMs, rightTimeMs).result;
    }

    /**
     * @param left landmarks in the primary camera, x0,y0,x1,y1,... (pixels, rectified)
     * @param right the same landmarks in the second camera, same order
     */
    public Analysis analyse(StereoCalibration calibration, double[] left, double[] right, double leftTimeMs,
            double rightTimeMs) {
        if (calibration == null || left == null || right == null) {
            return abstained(CueResult.AbstainReason.CAPABILITY_MISSING);
        }
        int n = left.length / 2;
        if (right.length != left.length || n < MIN_LANDMARKS) {
            return abstained(CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        if (Math.abs(leftTimeMs - rightTimeMs) > MAX_PAIR_SKEW_MS) {
            return abstained(CueResult.AbstainReason.NOT_APPLICABLE);
        }
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        double rowSq = 0;
        for (int i = 0; i < n; i++) {
            double disparity = left[2 * i] - right[2 * i];
            if (!(disparity > 0.5)) {
                return abstained(CueResult.AbstainReason.NOT_APPLICABLE); // at or beyond infinity: no usable depth
            }
            z[i] = calibration.depthMm(disparity);
            x[i] = (left[2 * i] - calibration.cx()) * z[i] / calibration.focalPx();
            y[i] = (left[2 * i + 1] - calibration.cy()) * z[i] / calibration.focalPx();
            double dy = left[2 * i + 1] - right[2 * i + 1];
            rowSq += dy * dy;
        }
        double rowError = Math.sqrt(rowSq / n);
        double residual = planeResidual(x, y, z);
        double cheekZ = 0;
        for (int c : cheeks) {
            cheekZ += z[c] / cheeks.length;
        }
        double relief = cheekZ - z[noseTip];

        double p;
        if (rowError > MAX_ROW_ERROR_PX) {
            p = 0.02; // the two views do not agree on the scene's geometry
        } else {
            p = 1 / (1 + Math.exp(-(relief - PROVISIONAL_RELIEF_MM) / RELIEF_SLOPE_MM));
        }
        CueResult vote = CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), relief);
        return new Analysis(vote, relief, residual, rowError);
    }

    private static Analysis abstained(CueResult.AbstainReason r) {
        return new Analysis(CueResult.abstain(NAME, r), Double.NaN, Double.NaN, Double.NaN);
    }

    /** RMS orthogonal distance of the 3-D points from the least-squares plane {@code z = a x + b y + c}. */
    static double planeResidual(double[] x, double[] y, double[] z) {
        int n = x.length;
        double sx = 0;
        double sy = 0;
        double sz = 0;
        double sxx = 0;
        double sxy = 0;
        double syy = 0;
        double sxz = 0;
        double syz = 0;
        for (int i = 0; i < n; i++) {
            sx += x[i];
            sy += y[i];
            sz += z[i];
            sxx += x[i] * x[i];
            sxy += x[i] * y[i];
            syy += y[i] * y[i];
            sxz += x[i] * z[i];
            syz += y[i] * z[i];
        }
        // Normal equations for [a b c], solved by Cramer's rule.
        double[][] m = {{sxx, sxy, sx}, {sxy, syy, sy}, {sx, sy, n}};
        double[] rhs = {sxz, syz, sz};
        double det = det3(m);
        if (Math.abs(det) < 1e-9) {
            return Double.NaN;
        }
        double a = det3(replace(m, 0, rhs)) / det;
        double b = det3(replace(m, 1, rhs)) / det;
        double c = det3(replace(m, 2, rhs)) / det;
        double norm = Math.sqrt(a * a + b * b + 1);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double d = (z[i] - (a * x[i] + b * y[i] + c)) / norm;
            sum += d * d;
        }
        return Math.sqrt(sum / n);
    }

    private static double[][] replace(double[][] m, int col, double[] v) {
        double[][] out = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r][c] = c == col ? v[r] : m[r][c];
            }
        }
        return out;
    }

    private static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
}
