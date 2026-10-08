/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import pramaan.core.landmarks.FaceLandmarks;

/**
 * Yaw from six landmarks by perspective-n-point against a canonical 3-D face (Bible Part 11.3; Build Map
 * #45), solved here with Levenberg-Marquardt so core needs no OpenCV.
 *
 * <p><strong>Conventions</strong> (the classic source of sign bugs): camera coordinates are x right, y
 * down, z away from the camera. The canonical face looks along -z, towards the camera. Yaw is the angle
 * of the face's forward vector {@code R * (0, 0, -1)} towards +x: <strong>positive yaw = the nose
 * points to the image's right = the resident has turned to their own left</strong> (unmirrored frames).
 *
 * <p><strong>Use {@link #relativeYawDegrees} for challenges, not {@link #yawDegrees}.</strong> A canonical
 * face cannot know a person's proportions, and the error is multiplicative, not an offset: measured on
 * rendered faces, the canonical model reads a true 20 degree turn as 13 (wide-set eyes, short chin, flatter
 * nose) up to 27 (the opposite). A resident with a flatter face would have to turn ~30 degrees to pass a
 * "20 degree" challenge: a demographic bias built into the geometry (Bible 47.8). {@code yawDegrees}
 * stays for absolute guidance ("face the camera"), where a few degrees do not matter.
 */
public final class HeadPoseEstimator {

    /** MediaPipe indices: nose tip, chin, right eye outer, left eye outer, right mouth, left mouth. */
    static final int[] INDICES = {1, 152, 33, 263, 61, 291};
    /** The canonical face (OpenCV's head-pose model converted to x right, y down, z away; ~0.2 mm units). */
    static final double[][] MODEL = {
        {0, 0, 0},
        {0, 330, 65},
        {-225, -170, 135},
        {225, -170, 135},
        {-150, 150, 125},
        {150, 150, 125},
    };

    private HeadPoseEstimator() {
    }

    /** Yaw in degrees for landmarks in a frame of the given size, focal length taken as the frame width. */
    public static double yawDegrees(FaceLandmarks lm, int frameWidth, int frameHeight) {
        double[] image = new double[INDICES.length * 2];
        for (int i = 0; i < INDICES.length; i++) {
            image[2 * i] = lm.x(INDICES[i]);
            image[2 * i + 1] = lm.y(INDICES[i]);
        }
        return yawDegrees(solve(MODEL, image, frameWidth, frameWidth / 2.0, frameHeight / 2.0));
    }

    /**
     * Head rotation since the resident's own frontal frame, from all 468 mesh points in 3-D (MediaPipe's
     * per-landmark depth is in x's pixel scale): the rotation best aligning the two point sets (Horn's
     * quaternion method), reported as yaw. No canonical face is involved, so face proportions cancel out.
     * Accuracy rests on MediaPipe's depth estimates, which must be checked on corpus recordings at known
     * angles (#65).
     */
    public static double relativeYawDegrees(FaceLandmarks frontal, FaceLandmarks current) {
        int n = 468;
        double[] ca = new double[3];
        double[] cb = new double[3];
        for (int i = 0; i < n; i++) {
            ca[0] += frontal.x(i);
            ca[1] += frontal.y(i);
            ca[2] += frontal.z(i);
            cb[0] += current.x(i);
            cb[1] += current.y(i);
            cb[2] += current.z(i);
        }
        for (int k = 0; k < 3; k++) {
            ca[k] /= n;
            cb[k] /= n;
        }
        double[][] s = new double[3][3];
        for (int i = 0; i < n; i++) {
            double[] a = {frontal.x(i) - ca[0], frontal.y(i) - ca[1], frontal.z(i) - ca[2]};
            double[] b = {current.x(i) - cb[0], current.y(i) - cb[1], current.z(i) - cb[2]};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    s[r][c] += a[r] * b[c];
                }
            }
        }
        double[][] nm = {
            {s[0][0] + s[1][1] + s[2][2], s[1][2] - s[2][1], s[2][0] - s[0][2], s[0][1] - s[1][0]},
            {s[1][2] - s[2][1], s[0][0] - s[1][1] - s[2][2], s[0][1] + s[1][0], s[2][0] + s[0][2]},
            {s[2][0] - s[0][2], s[0][1] + s[1][0], -s[0][0] + s[1][1] - s[2][2], s[1][2] + s[2][1]},
            {s[0][1] - s[1][0], s[2][0] + s[0][2], s[1][2] + s[2][1], -s[0][0] - s[1][1] + s[2][2]},
        };
        double[] q = largestEigenvector(nm);
        double w = q[0];
        double x = q[1];
        double y = q[2];
        double z = q[3];
        double[] r = {
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        };
        return yawDegrees(r);
    }

    /** Cyclic Jacobi on a symmetric 4x4 matrix; returns the unit eigenvector of the largest eigenvalue. */
    static double[] largestEigenvector(double[][] m) {
        double[][] a = new double[4][];
        double[][] v = new double[4][4];
        for (int i = 0; i < 4; i++) {
            a[i] = m[i].clone();
            v[i][i] = 1;
        }
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = 0;
            double diag = 0;
            for (int p = 0; p < 4; p++) {
                diag += a[p][p] * a[p][p];
                for (int q = p + 1; q < 4; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off <= 1e-30 * diag) {
                break;
            }
            for (int p = 0; p < 4; p++) {
                for (int q = p + 1; q < 4; q++) {
                    if (a[p][q] == 0) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = (theta >= 0 ? 1 : -1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double sn = t * c;
                    for (int k = 0; k < 4; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - sn * akq;
                        a[k][q] = sn * akp + c * akq;
                    }
                    for (int k = 0; k < 4; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - sn * aqk;
                        a[q][k] = sn * apk + c * aqk;
                    }
                    for (int k = 0; k < 4; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - sn * vkq;
                        v[k][q] = sn * vkp + c * vkq;
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (a[i][i] > a[best][best]) {
                best = i;
            }
        }
        double[] out = {v[0][best], v[1][best], v[2][best], v[3][best]};
        double norm = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
        for (int i = 0; i < 4; i++) {
            out[i] /= norm;
        }
        return out;
    }

    /** Yaw of a rotation matrix (row-major 3x3) under this class's convention. */
    static double yawDegrees(double[] r) {
        // forward = R * (0, 0, -1) = -(third column)
        double fx = -r[2];
        double fz = -r[8];
        return Math.toDegrees(Math.atan2(fx, -fz));
    }

    /** Pose [rx, ry, rz, tx, ty, tz] -> rotation matrix, minimising reprojection error. */
    static double[] solve(double[][] model, double[] image, double f, double cx, double cy) {
        int n = model.length;
        double eyeModel = Math.hypot(model[3][0] - model[2][0], model[3][1] - model[2][1]);
        double eyeImage = Math.hypot(image[6] - image[4], image[7] - image[5]);
        double tz = f * eyeModel / eyeImage;
        double[] p = {0, 0, 0, (image[0] - cx) * tz / f, (image[1] - cy) * tz / f, tz};
        double lambda = 1e-3;
        double cost = cost(model, image, f, cx, cy, p);
        for (int iter = 0; iter < 200; iter++) {
            double[] r0 = residuals(model, image, f, cx, cy, p);
            double[][] j = new double[2 * n][6];
            for (int k = 0; k < 6; k++) {
                double h = k < 3 ? 1e-6 : 1e-4 * Math.max(1, Math.abs(p[k]));
                double[] q = p.clone();
                q[k] += h;
                double[] r1 = residuals(model, image, f, cx, cy, q);
                for (int i = 0; i < 2 * n; i++) {
                    j[i][k] = (r1[i] - r0[i]) / h;
                }
            }
            double[][] a = new double[6][6];
            double[] g = new double[6];
            for (int i = 0; i < 2 * n; i++) {
                for (int k = 0; k < 6; k++) {
                    g[k] += j[i][k] * r0[i];
                    for (int m = 0; m < 6; m++) {
                        a[k][m] += j[i][k] * j[i][m];
                    }
                }
            }
            boolean improved = false;
            for (int attempt = 0; attempt < 10 && !improved; attempt++) {
                double[][] damped = new double[6][6];
                double[] rhs = new double[6];
                for (int k = 0; k < 6; k++) {
                    damped[k] = a[k].clone();
                    damped[k][k] += lambda * Math.max(a[k][k], 1e-12);
                    rhs[k] = -g[k];
                }
                double[] delta = Linear.solve(damped, rhs);
                double[] q = p.clone();
                for (int k = 0; k < 6; k++) {
                    q[k] += delta[k];
                }
                double c = cost(model, image, f, cx, cy, q);
                if (c < cost) {
                    p = q;
                    improved = cost - c > 1e-12 * cost;
                    cost = c;
                    lambda = Math.max(lambda / 3, 1e-9);
                    if (!improved) {
                        return rotation(p);
                    }
                } else {
                    lambda *= 5;
                }
            }
            if (!improved) {
                break;
            }
        }
        return rotation(p);
    }

    private static double cost(double[][] model, double[] image, double f, double cx, double cy, double[] p) {
        double s = 0;
        for (double r : residuals(model, image, f, cx, cy, p)) {
            s += r * r;
        }
        return s;
    }

    private static double[] residuals(double[][] model, double[] image, double f, double cx, double cy, double[] p) {
        double[] r = rotation(p);
        double[] out = new double[model.length * 2];
        for (int i = 0; i < model.length; i++) {
            double[] x = model[i];
            double xc = r[0] * x[0] + r[1] * x[1] + r[2] * x[2] + p[3];
            double yc = r[3] * x[0] + r[4] * x[1] + r[5] * x[2] + p[4];
            double zc = r[6] * x[0] + r[7] * x[1] + r[8] * x[2] + p[5];
            out[2 * i] = f * xc / zc + cx - image[2 * i];
            out[2 * i + 1] = f * yc / zc + cy - image[2 * i + 1];
        }
        return out;
    }

    /** Rodrigues: rotation vector -> row-major matrix. */
    static double[] rotation(double[] p) {
        double theta = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (theta < 1e-12) {
            return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        double kx = p[0] / theta;
        double ky = p[1] / theta;
        double kz = p[2] / theta;
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double v = 1 - c;
        return new double[] {
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
        };
    }

    /** Small dense solver for the 6x6 normal equations. */
    static final class Linear {
        private Linear() {
        }

        static double[] solve(double[][] a, double[] b) {
            int n = b.length;
            double[][] m = new double[n][];
            double[] y = b.clone();
            for (int i = 0; i < n; i++) {
                m[i] = a[i].clone();
            }
            for (int c = 0; c < n; c++) {
                int piv = c;
                for (int r = c + 1; r < n; r++) {
                    if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) {
                        piv = r;
                    }
                }
                double[] t = m[c];
                m[c] = m[piv];
                m[piv] = t;
                double tb = y[c];
                y[c] = y[piv];
                y[piv] = tb;
                for (int r = c + 1; r < n; r++) {
                    double f = m[r][c] / m[c][c];
                    for (int k = c; k < n; k++) {
                        m[r][k] -= f * m[c][k];
                    }
                    y[r] -= f * y[c];
                }
            }
            double[] x = new double[n];
            for (int r = n - 1; r >= 0; r--) {
                double s = y[r];
                for (int k = r + 1; k < n; k++) {
                    s -= m[r][k] * x[k];
                }
                x[r] = s / m[r][r];
            }
            return x;
        }
    }
}
