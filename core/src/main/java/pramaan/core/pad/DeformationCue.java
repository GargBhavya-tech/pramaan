/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

/**
 * Non-rigid deformation cue (Bible Part 8.4; Build Map #33). A smile or eye closure deforms the face; a
 * mask or photo can only move rigidly. Fit a similarity transform to rigid points (eye corners, nose
 * bridge) between baseline and action frames, then measure how far the moving points (mouth, eyelids)
 * depart from it, in units of IOD. Threshold PROVISIONAL.
 */
public final class DeformationCue {

    public static final String NAME = "deformation";
    /** PROVISIONAL: non-rigid residual (fraction of IOD) at which live and rigid are equally likely. */
    public static final double PROVISIONAL_THRESHOLD = 0.04;

    public CueResult evaluate(double[] rigid0, double[] rigid1, double[] moving0, double[] moving1, double iod) {
        double r = nonRigidResidual(rigid0, rigid1, moving0, moving1) / iod;
        double p = 1 / (1 + Math.exp(-(r - PROVISIONAL_THRESHOLD) / 0.01));
        return CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), r);
    }

    /** Mean distance (px) of moving points from where the rigid similarity transform predicts them. */
    static double nonRigidResidual(double[] rigid0, double[] rigid1, double[] moving0, double[] moving1) {
        double sum = 0;
        double[] each = perPointResidual(rigid0, rigid1, moving0, moving1);
        for (double d : each) {
            sum += d;
        }
        return sum / each.length;
    }

    /**
     * Each moving point's distance (px) from where the similarity transform fitted to the rigid points
     * predicts it. The smile detector (#44) uses this to require that both mouth corners moved.
     */
    public static double[] perPointResidual(double[] rigid0, double[] rigid1, double[] moving0, double[] moving1) {
        int n = rigid0.length / 2;
        double mx0 = 0;
        double my0 = 0;
        double mx1 = 0;
        double my1 = 0;
        for (int i = 0; i < n; i++) {
            mx0 += rigid0[2 * i];
            my0 += rigid0[2 * i + 1];
            mx1 += rigid1[2 * i];
            my1 += rigid1[2 * i + 1];
        }
        mx0 /= n;
        my0 /= n;
        mx1 /= n;
        my1 /= n;
        double sa = 0;
        double sb = 0;
        double ss = 0;
        for (int i = 0; i < n; i++) {
            double x0 = rigid0[2 * i] - mx0;
            double y0 = rigid0[2 * i + 1] - my0;
            double x1 = rigid1[2 * i] - mx1;
            double y1 = rigid1[2 * i + 1] - my1;
            sa += x0 * x1 + y0 * y1;
            sb += x0 * y1 - y0 * x1;
            ss += x0 * x0 + y0 * y0;
        }
        double a = sa / ss;
        double b = sb / ss;
        int m = moving0.length / 2;
        double[] out = new double[m];
        for (int i = 0; i < m; i++) {
            double x = moving0[2 * i] - mx0;
            double y = moving0[2 * i + 1] - my0;
            double px = a * x - b * y + mx1;
            double py = b * x + a * y + my1;
            out[i] = Math.hypot(px - moving1[2 * i], py - moving1[2 * i + 1]);
        }
        return out;
    }
}
