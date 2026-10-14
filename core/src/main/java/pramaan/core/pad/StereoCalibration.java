/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

/**
 * Calibration of a rectified auxiliary-camera pair (Bible Part 38; Build Map #86): focal length in pixels, baseline
 * in millimetres, and the principal point. The geometry here is the rectified case — optical axes parallel, image rows
 * aligned — in which a landmark with disparity d has depth {@code Z = f * b / d}.
 *
 * <p>Stereo calibration and rectification themselves (OpenCV's {@code stereoCalibrate}/{@code stereoRectify} over
 * checkerboard views) happen at installation, outside this module; this record carries their result into the core.
 * {@link #baselineFromKnownTarget} recovers the baseline from a single reference target at a measured distance, which
 * is the daily self-test in Part 40, and {@link #drift} compares that against what was stored.
 */
public final class StereoCalibration {

    private final double focalPx;
    private final double baselineMm;
    private final double cx;
    private final double cy;

    public StereoCalibration(double focalPx, double baselineMm, double cx, double cy) {
        if (!(focalPx > 0) || !(baselineMm > 0)) {
            throw new IllegalArgumentException("focal length and baseline must be positive");
        }
        this.focalPx = focalPx;
        this.baselineMm = baselineMm;
        this.cx = cx;
        this.cy = cy;
    }

    /** Baseline from a target of known depth whose disparity was measured: {@code b = Z * d / f}. */
    public static double baselineFromKnownTarget(double focalPx, double depthMm, double disparityPx) {
        if (!(focalPx > 0) || !(depthMm > 0) || !(disparityPx > 0)) {
            throw new IllegalArgumentException("focal length, depth and disparity must be positive");
        }
        return depthMm * disparityPx / focalPx;
    }

    /** Relative drift of the baseline measured now against the stored one; 0 means unchanged. */
    public double drift(double measuredBaselineMm) {
        return Math.abs(measuredBaselineMm - baselineMm) / baselineMm;
    }

    /** Depth in mm for a disparity in pixels. */
    public double depthMm(double disparityPx) {
        return focalPx * baselineMm / disparityPx;
    }

    /** Depth error in mm for a disparity error: {@code dZ = Z^2 / (f b) * dd}. */
    public double depthResolutionMm(double depthMm, double disparityErrorPx) {
        return depthMm * depthMm / (focalPx * baselineMm) * disparityErrorPx;
    }

    public double focalPx() {
        return focalPx;
    }

    public double baselineMm() {
        return baselineMm;
    }

    public double cx() {
        return cx;
    }

    public double cy() {
        return cy;
    }
}
