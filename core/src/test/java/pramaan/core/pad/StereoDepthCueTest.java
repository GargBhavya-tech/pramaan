/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Bible Part 38 and Build Map #86: StereoTriangulationTest and StereoInjectionConsistencyTest. */
class StereoDepthCueTest {

    // The Bible's worked example: f = 900 px, b = 80 mm, subject at Z = 500 mm -> d = 144 px.
    private static final StereoCalibration CAL = new StereoCalibration(900, 80, 640, 360);
    private static final int NOSE = 0;
    private static final int[] CHEEKS = {1, 2};
    private static final int LANDMARKS = 30;

    /** A synthetic landmark set: [noseTip, cheekL, cheekR, 27 others], in mm relative to the face centre. */
    private static double[][] face(double reliefMm, double curvatureMm) {
        double[][] p = new double[LANDMARKS][3];
        Random r = new Random(11);
        p[0] = new double[] {0, 10, 500 - reliefMm};
        p[1] = new double[] {-55, 15, 500};
        p[2] = new double[] {55, 15, 500};
        for (int i = 3; i < LANDMARKS; i++) {
            double x = (r.nextDouble() - 0.5) * 120;
            double y = (r.nextDouble() - 0.5) * 140;
            // A bowl: the face (or a curved print) falls away toward the edges.
            p[i] = new double[] {x, y, 500 + curvatureMm * (x * x + y * y) / (70.0 * 70.0)};
        }
        return p;
    }

    /** Project a 3-D point set through the rectified pair (left camera at the origin, right at +baseline). */
    private static double[][] project(double[][] pts, double noisePx, long seed) {
        Random n = new Random(seed);
        double[] left = new double[2 * pts.length];
        double[] right = new double[2 * pts.length];
        for (int i = 0; i < pts.length; i++) {
            double x = pts[i][0];
            double y = pts[i][1];
            double z = pts[i][2];
            left[2 * i] = CAL.cx() + CAL.focalPx() * x / z + n.nextGaussian() * noisePx;
            left[2 * i + 1] = CAL.cy() + CAL.focalPx() * y / z + n.nextGaussian() * noisePx;
            right[2 * i] = CAL.cx() + CAL.focalPx() * (x - CAL.baselineMm()) / z + n.nextGaussian() * noisePx;
            right[2 * i + 1] = CAL.cy() + CAL.focalPx() * y / z + n.nextGaussian() * noisePx;
        }
        return new double[][] {left, right};
    }

    private static StereoDepthCue.Analysis run(double[][] views) {
        return new StereoDepthCue(NOSE, CHEEKS).analyse(CAL, views[0], views[1], 1000, 1008);
    }

    @Test
    void theWorkedExampleDisparityAndResolution() {
        assertEquals(500, CAL.depthMm(144), 1e-9);
        assertEquals(1.7, CAL.depthResolutionMm(500, 0.5), 0.1, "half a pixel of landmark error is about 1.7 mm");
    }

    @Test
    void aRealFaceShowsTwentyToThirtyMillimetresOfNoseRelief() {
        StereoDepthCue.Analysis a = run(project(face(25, 30), 0.15, 1));
        assertEquals(25, a.noseReliefMm, 4);
        assertTrue(a.planeResidualMm > 4, "residual " + a.planeResidualMm);
        assertTrue(a.result.pLive() > 0.9, "p " + a.result.pLive());
    }

    @Test
    void aFlatPrintHasNoRelief() {
        StereoDepthCue.Analysis a = run(project(face(0, 0), 0.15, 2));
        assertTrue(Math.abs(a.noseReliefMm) < 3, "relief " + a.noseReliefMm);
        assertTrue(a.planeResidualMm < 2.5, "residual " + a.planeResidualMm);
        assertTrue(a.result.pLive() < 0.1, "p " + a.result.pLive());
    }

    @Test
    void aCurvedPrintBendsButStillHasNoNosePeak() {
        StereoDepthCue.Analysis a = run(project(face(0, 30), 0.15, 3));
        assertTrue(a.planeResidualMm > 1.5, "a curved print is not on a plane: " + a.planeResidualMm);
        assertTrue(a.noseReliefMm < 5, "but it has no nose: " + a.noseReliefMm);
        assertTrue(a.result.pLive() < 0.2);
    }

    @Test
    void injectingIntoOneCameraRaisesTheRowError() {
        double[][] views = project(face(25, 30), 0.15, 4);
        // The attacker replaces the second camera's landmarks with a different scene (shifted and rescaled).
        Random r = new Random(5);
        for (int i = 0; i < LANDMARKS; i++) {
            views[1][2 * i + 1] += 6 + r.nextGaussian() * 3;
        }
        StereoDepthCue.Analysis a = run(views);
        assertTrue(a.rowErrorPx > StereoDepthCue.MAX_ROW_ERROR_PX, "row error " + a.rowErrorPx);
        assertTrue(a.result.pLive() < 0.05, "p " + a.result.pLive());
    }

    @Test
    void aGenuinePairHasRowErrorNearTheLandmarkNoise() {
        assertTrue(run(project(face(25, 30), 0.15, 6)).rowErrorPx < 0.5);
    }

    @Test
    void framesMoreThanTwentyMillisecondsApartAreNotAPair() {
        double[][] v = project(face(25, 30), 0.15, 7);
        CueResult r = new StereoDepthCue(NOSE, CHEEKS).evaluate(CAL, v[0], v[1], 1000, 1025);
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.NOT_APPLICABLE, r.abstainReason());
    }

    @Test
    void noSecondCameraMeansAbstain() {
        double[][] v = project(face(25, 30), 0.15, 8);
        CueResult r = new StereoDepthCue(NOSE, CHEEKS).evaluate(null, v[0], v[1], 1000, 1000);
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, r.abstainReason());
    }

    @Test
    void tooFewLandmarksAbstains() {
        CueResult r = new StereoDepthCue(NOSE, CHEEKS).evaluate(CAL, new double[6], new double[6], 0, 0);
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL, r.abstainReason());
    }

    @Test
    void theDailySelfTestDetectsADriftedBaseline() {
        double measured = StereoCalibration.baselineFromKnownTarget(900, 500, 144);
        assertEquals(80, measured, 1e-9);
        assertTrue(CAL.drift(measured) < 1e-9);
        assertTrue(CAL.drift(StereoCalibration.baselineFromKnownTarget(900, 500, 130)) > 0.05,
                "a bumped camera shows as baseline drift");
    }
}
