/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pramaan.core.landmarks.Blendshapes;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.FlatnessCue;

/** Build Map #43-#46 on scripted and rendered inputs with known answers. */
class ActionDetectorsTest {

    // ---------------------------------------------------------------- #43
    private static EyeStateDetector eyes(double baseline, boolean eitherEye, long holdMs) {
        EyeStateDetector d = new EyeStateDetector(eitherEye, holdMs);
        for (int i = 0; i < 10; i++) {
            d.addBaseline(baseline + (i % 3 - 1) * 0.005, baseline + (i % 3 - 1) * 0.005);
        }
        return d;
    }

    private static List<EyeEvent> run(EyeStateDetector d, long frameMs, double... ear) {
        List<EyeEvent> out = new ArrayList<>();
        for (int i = 0; i < ear.length; i++) {
            d.observe(i * frameMs, ear[i], ear[i]).ifPresent(out::add);
        }
        return out;
    }

    @Test
    void theBibleWorkedExampleDetectsExactlyOneBlink() {
        EyeStateDetector d = eyes(0.22, false, 1000);
        assertEquals(0.154, d.threshold(true), 1e-9);
        List<EyeEvent> events = run(d, 33, 0.22, 0.21, 0.12, 0.08, 0.11, 0.20, 0.22);
        assertEquals(1, events.size());
        assertEquals(EyeEvent.Type.BLINK, events.get(0).type());
        assertEquals(66, events.get(0).onset().latestMs(), "the blink starts on the first frame below theta");
        assertEquals(33, events.get(0).onset().earliestMs());
    }

    @Test
    void naturallyNarrowEyesAreNotReadAsAlwaysClosed() {
        // Baseline 0.18: a fixed 0.2 threshold would call every frame "closed". theta = 0.126.
        EyeStateDetector d = eyes(0.18, false, 1000);
        assertTrue(run(d, 33, 0.18, 0.17, 0.19, 0.18, 0.17, 0.18).isEmpty());
        assertEquals(1, run(eyes(0.18, false, 1000), 33, 0.18, 0.10, 0.07, 0.09, 0.18).size());
    }

    @Test
    void eyesClosedHoldWorksAtThreeFramesPerSecond() {
        EyeStateDetector d = eyes(0.25, false, 1000);
        List<EyeEvent> events = run(d, 333, 0.25, 0.08, 0.07, 0.07, 0.08, 0.25);
        assertEquals(1, events.size());
        assertEquals(EyeEvent.Type.CLOSED_HOLD, events.get(0).type());
        assertTrue(events.get(0).durationMs() >= 1000);
    }

    @Test
    void oneEyeClosingCountsOnlyForTheFacialMotorProfile() {
        for (boolean either : new boolean[] {false, true}) {
            EyeStateDetector d = eyes(0.25, either, 1000);
            List<EyeEvent> events = new ArrayList<>();
            double[] right = {0.25, 0.08, 0.07, 0.08, 0.25};
            for (int i = 0; i < right.length; i++) {
                d.observe(i * 33L, right[i], 0.25).ifPresent(events::add);
            }
            assertEquals(either ? 1 : 0, events.size(), "eitherEye=" + either);
        }
    }

    @Test
    void noBaselineMeansNoDecision() {
        assertThrows(IllegalStateException.class, () -> new EyeStateDetector(false, 1000).observe(0, 0.1, 0.1));
    }

    // ---------------------------------------------------------------- #44
    /** A synthetic frontal face in pixels: IOD 60, mouth 48 wide. */
    private static float[] face() {
        float[] xyz = new float[FaceLandmarks.COUNT * 3];
        set(xyz, FaceLandmarks.RIGHT_IRIS, 290, 200);
        set(xyz, FaceLandmarks.LEFT_IRIS, 350, 200);
        set(xyz, 33, 275, 200);
        set(xyz, 133, 305, 200);
        set(xyz, 362, 335, 200);
        set(xyz, 263, 365, 200);
        set(xyz, 168, 320, 200);
        set(xyz, 6, 320, 215);
        set(xyz, FaceLandmarks.MOUTH_RIGHT, 296, 280);
        set(xyz, FaceLandmarks.MOUTH_LEFT, 344, 280);
        return xyz;
    }

    private static void set(float[] xyz, int i, double x, double y) {
        xyz[3 * i] = (float) x;
        xyz[3 * i + 1] = (float) y;
    }

    /** Similarity (rotate, scale, translate) and an optional horizontal stretch — everything a photo can do rigidly. */
    private static float[] warp(float[] xyz, double deg, double scale, double stretchX, double tx, double ty) {
        float[] out = xyz.clone();
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        for (int i = 0; i < FaceLandmarks.COUNT; i++) {
            double x = (xyz[3 * i] - 320) * stretchX;
            double y = xyz[3 * i + 1] - 240;
            out[3 * i] = (float) (scale * (c * x - s * y) + 320 + tx);
            out[3 * i + 1] = (float) (scale * (s * x + c * y) + 240 + ty);
        }
        return out;
    }

    @Test
    void aRealSmilePassesAndATiltedOrStretchedPhotoDoesNot() {
        SmileDetector d = new SmileDetector(new FaceLandmarks(face(), 1));
        float[] smile = face();
        set(smile, FaceLandmarks.MOUTH_RIGHT, 291, 277);
        set(smile, FaceLandmarks.MOUTH_LEFT, 349, 277);
        SmileDetector.Reading real = d.read(new FaceLandmarks(smile, 1));
        assertTrue(real.smiling(), "gain " + real.widthGain() + " residual " + real.residualIod());

        SmileDetector.Reading tilted = d.read(new FaceLandmarks(warp(face(), 10, 1.1, 1, 12, -4), 1));
        SmileDetector.Reading stretched = d.read(new FaceLandmarks(warp(face(), 0, 1, 1.15, 0, 0), 1));
        assertFalse(tilted.smiling());
        assertFalse(stretched.smiling());
        assertTrue(Math.abs(tilted.widthGain()) < 0.01 && tilted.residualIod() < 0.01, "a rigid photo does not deform");
        assertTrue(Math.abs(stretched.widthGain()) < 0.01, "width/IOD is invariant to stretching the photo");
    }

    @Test
    void aOneSidedMovementIsNotASmile() {
        SmileDetector d = new SmileDetector(new FaceLandmarks(face(), 1));
        float[] half = face();
        set(half, FaceLandmarks.MOUTH_LEFT, 352, 276);
        SmileDetector.Reading r = d.read(new FaceLandmarks(half, 1));
        assertFalse(r.smiling());
        assertTrue(r.rightCornerIod() < SmileDetector.MIN_CORNER_MOVE_IOD);
    }

    private static float[] blend(double smile, double blink) {
        float[] b = new float[Blendshapes.COUNT];
        b[Blendshapes.MOUTH_SMILE_LEFT] = (float) smile;
        b[Blendshapes.MOUTH_SMILE_RIGHT] = (float) smile;
        b[Blendshapes.EYE_BLINK_LEFT] = (float) blink;
        b[Blendshapes.EYE_BLINK_RIGHT] = (float) blink;
        return b;
    }

    @Test
    void theBlendshapeVoteStandsInForMouthWidthButNeverForDeformation() {
        // Corners lift 5 px without widening: a smile that fails the 12% width test on geometry alone.
        float[] lift = face();
        set(lift, FaceLandmarks.MOUTH_RIGHT, 296, 275);
        set(lift, FaceLandmarks.MOUTH_LEFT, 344, 275);
        SmileDetector plain = new SmileDetector(new FaceLandmarks(face(), 1));
        assertFalse(plain.read(new FaceLandmarks(lift, 1)).smiling(), "no blendshapes: width decides");

        SmileDetector d = new SmileDetector(new FaceLandmarks(face(), 1).withBlendshapes(blend(0.1, 0)));
        SmileDetector.Reading r = d.read(new FaceLandmarks(lift, 1).withBlendshapes(blend(0.8, 0)));
        assertTrue(r.blendshapeSmile() && r.smiling(), "gain " + r.widthGain() + " residual " + r.residualIod());
        assertFalse(d.read(new FaceLandmarks(lift, 1).withBlendshapes(blend(0.45, 0))).smiling(), "below 0.5");
        assertFalse(new SmileDetector(new FaceLandmarks(face(), 1).withBlendshapes(blend(0.6, 0)))
                .read(new FaceLandmarks(lift, 1).withBlendshapes(blend(0.8, 0))).blendshapeSmile(), "risen only 0.2");

        // A photo of a smiling face, tilted: the model may score it smiling, but nothing deforms.
        SmileDetector.Reading photo = d.read(new FaceLandmarks(warp(face(), 10, 1.1, 1, 12, -4), 1).withBlendshapes(blend(0.9, 0)));
        assertTrue(photo.blendshapeSmile());
        assertFalse(photo.smiling(), "the deformation tests still apply");
    }

    private static EyeStateDetector eyesWithBlink(double ear, double blink) {
        EyeStateDetector d = new EyeStateDetector(false, 1000);
        for (int i = 0; i < 10; i++) {
            d.addBaseline(ear, ear, blink, blink);
        }
        return d;
    }

    private static FaceLandmarks eyeFace(double blink) {
        float[] xyz = face();
        // Open eyes, EAR 0.3 on both sides.
        for (int[] p : new int[][] {FaceLandmarks.RIGHT_EYE_EAR, FaceLandmarks.LEFT_EYE_EAR}) {
            double x0 = xyz[3 * p[0]];
            double x3 = xyz[3 * p[3]];
            double w = x3 - x0;
            set(xyz, p[1], x0 + w / 3, 200 - 0.3 * w);
            set(xyz, p[2], x0 + 2 * w / 3, 200 - 0.3 * w);
            set(xyz, p[5], x0 + w / 3, 200);
            set(xyz, p[4], x0 + 2 * w / 3, 200);
        }
        return new FaceLandmarks(xyz, 1).withBlendshapes(blend(0, blink));
    }

    @Test
    void blinkBlendshapesAreASecondVoteRelativeToThePersonsOwnBaseline() {
        assertEquals(0.3, eyeFace(0).eyeAspectRatio(true), 1e-6);
        // Geometry says open throughout; the blendshapes say the eyes closed for a second.
        EyeStateDetector d = eyesWithBlink(0.3, 0.05);
        List<EyeEvent> events = new ArrayList<>();
        double[] blink = {0.05, 0.9, 0.9, 0.9, 0.9, 0.05};
        for (int i = 0; i < blink.length; i++) {
            d.observe(i * 333L, eyeFace(blink[i])).ifPresent(events::add);
        }
        assertEquals(1, events.size());
        assertEquals(EyeEvent.Type.CLOSED_HOLD, events.get(0).type());

        // A resident whose resting eyeBlink is already 0.6 (ptosis) is not read as closed at 0.7.
        EyeStateDetector droopy = eyesWithBlink(0.3, 0.6);
        assertFalse(droopy.blendshapeClosed(eyeFace(0.7)));
        assertTrue(droopy.blendshapeClosed(eyeFace(0.95)));
        // Without a blendshape baseline, there is no vote.
        assertFalse(eyes(0.3, false, 1000).blendshapeClosed(eyeFace(0.95)));
    }

    // ---------------------------------------------------------------- #45
    /** Renders the six pose landmarks of a face at a given facing yaw, with pitch, roll and optional proportion changes. */
    static float[] render(double yawDeg, double pitchDeg, double rollDeg, double chinScale, double eyeScale, double noise) {
        double[][] model = new double[HeadPoseEstimator.MODEL.length][];
        for (int i = 0; i < model.length; i++) {
            model[i] = HeadPoseEstimator.MODEL[i].clone();
        }
        model[1][1] *= chinScale;
        model[2][0] *= eyeScale;
        model[3][0] *= eyeScale;
        // Facing yaw theta needs Ry(-theta) under the estimator's convention; then pitch about x, roll about z.
        double[] r = mul(mul(rz(rollDeg), rx(pitchDeg)), ry(-yawDeg));
        float[] xyz = new float[FaceLandmarks.COUNT * 3];
        double f = 640;
        for (int i = 0; i < model.length; i++) {
            double[] x = model[i];
            double xc = r[0] * x[0] + r[1] * x[1] + r[2] * x[2];
            double yc = r[3] * x[0] + r[4] * x[1] + r[5] * x[2];
            double zc = r[6] * x[0] + r[7] * x[1] + r[8] * x[2] + 3000;
            double dx = noise * Math.sin(i * 12.9898 + yawDeg);
            double dy = noise * Math.cos(i * 78.233 + yawDeg);
            set(xyz, HeadPoseEstimator.INDICES[i], f * xc / zc + 320 + dx, f * yc / zc + 240 + dy);
        }
        return xyz;
    }

    private static double[] ry(double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[] {c, 0, s, 0, 1, 0, -s, 0, c};
    }

    private static double[] rx(double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[] {1, 0, 0, 0, c, -s, 0, s, c};
    }

    private static double[] rz(double deg) {
        double c = Math.cos(Math.toRadians(deg));
        double s = Math.sin(Math.toRadians(deg));
        return new double[] {c, -s, 0, s, c, 0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    out[3 * i + j] += a[3 * i + k] * b[3 * k + j];
                }
            }
        }
        return out;
    }

    @Test
    void theCanonicalSolverRecoversRenderedYaw() {
        for (int yaw : new int[] {-30, -20, -10, 0, 10, 20, 30}) {
            double exact = HeadPoseEstimator.yawDegrees(new FaceLandmarks(render(yaw, 6, -3, 1, 1, 0), 1), 640, 480);
            assertEquals(yaw, exact, 0.5, "noise-free yaw " + yaw);
            // 0.8 px of landmark jitter on a ~100 px face moves yaw by up to ~2 degrees: physics, not the solver.
            double noisy = HeadPoseEstimator.yawDegrees(new FaceLandmarks(render(yaw, 6, -3, 1, 1, 0.8), 1), 640, 480);
            assertEquals(yaw, noisy, 2.5, "noisy yaw " + yaw);
        }
    }

    @Test
    void positiveYawMeansTheNoseMovedToTheImageRightThatIsTheResidentsLeft() {
        float[] xyz = render(20, 0, 0, 1, 1, 0);
        double nose = xyz[3 * 1];
        double eyeMid = (xyz[3 * 33] + xyz[3 * 263]) / 2;
        assertTrue(nose > eyeMid, "at +20 deg the nose must sit right of the eye midpoint");
        assertEquals(1, TurnDirection.LEFT.yawSign());
    }

    /** A 468-point 3-D face (px units) with adjustable proportions, rendered with MediaPipe-style depth. */
    static FaceLandmarks mesh(double yawDeg, double pitchDeg, double widthScale, double depthScale, double noise) {
        float[] xyz = new float[FaceLandmarks.COUNT * 3];
        double[] r = mul(rx(pitchDeg), ry(-yawDeg));
        double f = 640;
        double dist = 600;
        for (int i = 0; i < FaceLandmarks.COUNT; i++) {
            double u = (i % 22) / 21.0 * 2 - 1;
            double v = (i / 22) / 21.0 * 2 - 1;
            double x = 70 * u * widthScale;
            double y = 90 * v;
            double z = depthScale * (-40 * Math.exp(-(u * u + v * v) * 4) + 25 * (u * u + v * v));
            double xc = r[0] * x + r[1] * y + r[2] * z;
            double yc = r[3] * x + r[4] * y + r[5] * z;
            double zc = r[6] * x + r[7] * y + r[8] * z + dist;
            xyz[3 * i] = (float) (f * xc / zc + 320 + noise * Math.sin(i * 12.9898 + yawDeg));
            xyz[3 * i + 1] = (float) (f * yc / zc + 240 + noise * Math.cos(i * 78.233 + yawDeg));
            xyz[3 * i + 2] = (float) (f * (zc - dist) / dist);
        }
        return new FaceLandmarks(xyz, 1);
    }

    @Test
    void theCanonicalModelIsBiasedByFaceProportions() {
        // Wide-set eyes, short chin, flatter nose: a true 20 degree turn reads as ~13. This is why challenges
        // use relative yaw on the person's own 3-D landmarks instead.
        double base = HeadPoseEstimator.yawDegrees(new FaceLandmarks(renderShaped(0, 0.9, 1.08, 0.8), 1), 640, 480);
        double turned = HeadPoseEstimator.yawDegrees(new FaceLandmarks(renderShaped(20, 0.9, 1.08, 0.8), 1), 640, 480);
        assertTrue(turned - base < 15, "canonical PnP underestimates this face: " + (turned - base));
    }

    /** Six pose landmarks of a face whose chin, eye spacing and depth differ from the canonical model. */
    private static float[] renderShaped(double yawDeg, double chin, double eye, double depth) {
        double[] r = mul(mul(rz(2), rx(4)), ry(-yawDeg));
        float[] xyz = new float[FaceLandmarks.COUNT * 3];
        for (int i = 0; i < HeadPoseEstimator.MODEL.length; i++) {
            double[] x = HeadPoseEstimator.MODEL[i].clone();
            x[2] *= depth;
            if (i == 1) {
                x[1] *= chin;
            }
            if (i == 2 || i == 3) {
                x[0] *= eye;
            }
            double xc = r[0] * x[0] + r[1] * x[1] + r[2] * x[2];
            double yc = r[3] * x[0] + r[4] * x[1] + r[5] * x[2];
            double zc = r[6] * x[0] + r[7] * x[1] + r[8] * x[2] + 3000;
            set(xyz, HeadPoseEstimator.INDICES[i], 640 * xc / zc + 320, 640 * yc / zc + 240);
        }
        return xyz;
    }

    @Test
    void relativeYawOnTheResidentsOwnLandmarksIsUnbiasedAcrossFaceShapes() {
        double[][] shapes = {{1, 1}, {1.1, 0.7}, {0.9, 1.3}, {1.15, 0.8}, {0.85, 1.2}};
        for (double[] shape : shapes) {
            FaceLandmarks frontal = mesh(0, 5, shape[0], shape[1], 0.5);
            for (int yaw : new int[] {-30, -20, -15, 15, 20, 30}) {
                double rel = HeadPoseEstimator.relativeYawDegrees(frontal, mesh(yaw, 5, shape[0], shape[1], 0.5));
                assertEquals(yaw, rel, 2.0, "width x" + shape[0] + " depth x" + shape[1] + " at " + yaw);
            }
        }
    }

    /** A 468-point face-like 3-D cloud (px), rendered at a yaw; flat=true gives a printed photo of it. */
    private static FaceLandmarks cloud(double yawDeg, boolean flat) {
        float[] xyz = new float[FaceLandmarks.COUNT * 3];
        double[] r = ry(-yawDeg);
        for (int i = 0; i < FaceLandmarks.COUNT; i++) {
            double u = (i % 22) / 21.0 * 2 - 1;
            double v = (i / 22) / 21.0 * 2 - 1;
            double x = 70 * u;
            double y = 90 * v;
            double z = flat ? 0 : -40 * Math.exp(-(u * u + v * v) * 4) + 25 * (u * u + v * v);
            double xc = r[0] * x + r[2] * z;
            double zc = r[6] * x + r[8] * z + 600;
            set(xyz, i, 900 * xc / zc + 320, 900 * y / zc + 240);
        }
        set(xyz, FaceLandmarks.RIGHT_IRIS, 290, 200);
        set(xyz, FaceLandmarks.LEFT_IRIS, 350, 200);
        return new FaceLandmarks(xyz, 1);
    }

    private static StepStatus runTurn(TurnDetector t, long frameMs, double[] yaws, boolean flat) {
        StepStatus s = StepStatus.IN_PROGRESS;
        for (int i = 0; i < yaws.length; i++) {
            s = t.observe(i * frameMs, yaws[i], cloud(yaws[i], flat));
        }
        return s;
    }

    @Test
    void aTurnPassesAndUnlocksAFlatnessVerdictAsASideEffect() {
        double[] yaws = {0, 0, 4, 9, 15, 20, 22, 22, 22, 22, 22, 22, 22, 22, 22, 18, 10, 6, 2};
        TurnDetector live = new TurnDetector(Challenge.turn(TurnDirection.LEFT, 20), 300, 0, cloud(0, false));
        assertEquals(StepStatus.PASS, runTurn(live, 33, yaws, false));
        TurnDetector shy = new TurnDetector(Challenge.turn(TurnDirection.LEFT, 20), 300, 0, null);
        double[] partial = {0, 5, 10, 15, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 8, 2};
        assertEquals(StepStatus.PASS, runTurn(shy, 33, partial, false), "75% of the commanded angle is enough");
        assertEquals(99, live.onset().orElseThrow().latestMs(), "onset on the first frame 5+ degrees from baseline");
        CueResult real = live.flatness(new FlatnessCue());
        assertFalse(real.abstained());
        assertTrue(real.pLive() > 0.9, "3-D face, residual " + real.rawValue());

        TurnDetector print = new TurnDetector(Challenge.turn(TurnDirection.LEFT, 20), 300, 0, cloud(0, true));
        assertEquals(StepStatus.PASS, runTurn(print, 33, yaws, true), "a printed photo can be turned too...");
        assertTrue(print.flatness(new FlatnessCue()).pLive() < 0.1, "...but it is flat");
    }

    @Test
    void wrongWayShortHoldAndLimitedMotion() {
        assertEquals(StepStatus.WRONG_ACTION, runTurn(new TurnDetector(Challenge.turn(TurnDirection.LEFT, 20), 300, 0, null),
                33, new double[] {0, -4, -8, -12}, false));
        // At the target for ~200 ms only, then back: not held long enough.
        double[] brief = {0, 8, 20, 21, 21, 21, 21, 21, 10, 0};
        assertEquals(StepStatus.IN_PROGRESS, runTurn(new TurnDetector(Challenge.turn(TurnDirection.LEFT, 20), 300, 0, null),
                33, brief, false));
        assertEquals(StepStatus.PASS, runTurn(new TurnDetector(Challenge.turn(TurnDirection.LEFT, 20), 150, 0, null),
                33, brief, false), "LIMITED_MOTION's 150 ms hold");
        assertTrue(TurnDetector.unrequestedTurn(25, 2));
        assertFalse(TurnDetector.unrequestedTurn(12, 0), "small head movement during a blink step is not an action");
    }

    // ---------------------------------------------------------------- #46
    @Test
    void latencyRules() {
        Duration min = Duration.ofMillis(250);
        Duration timeout = Duration.ofMillis(5000);
        assertEquals(LatencyRule.Verdict.TOO_EARLY, LatencyRule.judge(1000, new Onset(1067, 1100), min, timeout, true).verdict());
        assertEquals(LatencyRule.Verdict.OK, LatencyRule.judge(1000, new Onset(1567, 1600), min, timeout, true).verdict());
        LatencyRule.Result fast = LatencyRule.judge(1000, new Onset(1167, 1200), min, timeout, true);
        assertEquals(LatencyRule.Verdict.FAST, fast.verdict());
        assertTrue(fast.passed());
        assertEquals(LatencyRule.Verdict.TIMEOUT, LatencyRule.judge(1000, new Onset(6100, 6133), min, timeout, true).verdict());

        LatencyRule.Result lowFps = LatencyRule.judge(1000, new Onset(767, 1100), min, timeout, false);
        assertEquals(LatencyRule.Verdict.OK, lowFps.verdict(), "at 3 fps 100 ms cannot be resolved");
        assertTrue(lowFps.earlyRuleInert(), "and the result says the rule was inert");
        assertEquals(LatencyRule.Verdict.TOO_EARLY, LatencyRule.judge(1000, new Onset(600, 933), min, timeout, false).verdict(),
                "an action already visible before the prompt fails at any frame rate");
        assertTrue(LatencyRule.timedOut(1000, 6001, timeout));
        assertFalse(LatencyRule.timedOut(1000, 6000, timeout));
    }

    @Test
    void aReplayedClipWithTheRightActionStillFailsOnTiming() {
        // The clip happens to show a blink 100 ms after the prompt appeared: right action, impossible timing.
        EyeStateDetector d = eyes(0.25, false, 1000);
        Optional<EyeEvent> blink = Optional.empty();
        double[] ear = {0.25, 0.25, 0.25, 0.25, 0.08, 0.07, 0.08, 0.25};
        for (int i = 0; i < ear.length && blink.isEmpty(); i++) {
            blink = d.observe(i * 33L, ear[i], ear[i]);
        }
        long promptShown = 32;
        LatencyRule.Result r = LatencyRule.judge(promptShown, blink.orElseThrow().onset(), Duration.ofMillis(250),
                Duration.ofMillis(5000), true);
        assertEquals(LatencyRule.Verdict.TOO_EARLY, r.verdict(), "latency " + r.latencyMs());
    }
}
