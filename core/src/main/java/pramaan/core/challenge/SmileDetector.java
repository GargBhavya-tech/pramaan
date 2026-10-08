/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Objects;
import pramaan.core.landmarks.Blendshapes;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.DeformationCue;

/**
 * Smile verified by deformation, not by width alone (Bible Part 11.2; Build Map #44). All three must hold
 * against the passive-window baseline:
 * <ol>
 *   <li>mouth width / IOD up at least 12%, <strong>or</strong>, where blendshapes are computed, both
 *       {@code mouthSmileLeft/Right} at least {@value #MIN_BLENDSHAPE} and each up {@value #MIN_BLENDSHAPE_RISE} over
 *       the baseline (a smile that barely widens the mouth — some faces, some facial palsy — still counts);</li>
 *   <li>mean non-rigid residual of the mouth corners (Part 8.4) at least the deformation threshold;</li>
 *   <li><strong>both</strong> corners moved non-rigidly — a photo bent on one side moves one corner.</li>
 * </ol>
 * A tilted, scaled or stretched photo keeps width/IOD constant and leaves no non-rigid residual.
 * The blendshape vote replaces only the width test: the deformation tests still apply, so it cannot let a bent photo
 * through (a flat print has no non-rigid residual whatever a model scores it).
 * Thresholds are PROVISIONAL until the corpus (#65).
 */
public final class SmileDetector {

    public static final double MIN_WIDTH_GAIN = 0.12;
    public static final double MIN_CORNER_MOVE_IOD = 0.02;
    /** Blendshape vote: both mouthSmile scores at least this, and each risen by {@link #MIN_BLENDSHAPE_RISE}. */
    public static final double MIN_BLENDSHAPE = 0.5;
    public static final double MIN_BLENDSHAPE_RISE = 0.3;
    /** Rigid anchors: eye corners and nose bridge. */
    static final int[] RIGID = {33, 133, 362, 263, 168, 6};
    static final int[] CORNERS = {FaceLandmarks.MOUTH_RIGHT, FaceLandmarks.MOUTH_LEFT};

    /** One frame's measurements. */
    public static final class Reading {
        private final double widthGain;
        private final double residualIod;
        private final double rightCornerIod;
        private final double leftCornerIod;
        private final boolean blendshapeSmile;

        Reading(double widthGain, double residualIod, double rightCornerIod, double leftCornerIod, boolean blendshapeSmile) {
            this.blendshapeSmile = blendshapeSmile;
            this.widthGain = widthGain;
            this.residualIod = residualIod;
            this.rightCornerIod = rightCornerIod;
            this.leftCornerIod = leftCornerIod;
        }

        public double widthGain() {
            return widthGain;
        }

        public double residualIod() {
            return residualIod;
        }

        public double rightCornerIod() {
            return rightCornerIod;
        }

        public double leftCornerIod() {
            return leftCornerIod;
        }

        /** Both mouthSmile blendshapes high and risen over the baseline; false when there are no blendshapes. */
        public boolean blendshapeSmile() {
            return blendshapeSmile;
        }

        public boolean smiling() {
            return (widthGain >= MIN_WIDTH_GAIN || blendshapeSmile) && residualIod >= DeformationCue.PROVISIONAL_THRESHOLD
                    && rightCornerIod >= MIN_CORNER_MOVE_IOD && leftCornerIod >= MIN_CORNER_MOVE_IOD;
        }

        /** Movement has visibly started: half the width gain. Used as the onset for the latency rule. */
        boolean started() {
            return widthGain >= MIN_WIDTH_GAIN / 2 || blendshapeSmile;
        }
    }

    private final FaceLandmarks baseline;
    private final double baselineRatio;
    private Onset onset;
    private FaceLandmarks peak;
    private long lastMs = Long.MIN_VALUE;

    public SmileDetector(FaceLandmarks baseline) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.baselineRatio = widthRatio(baseline);
    }

    public Reading read(FaceLandmarks lm) {
        double iod = iod(baseline);
        double[] each = DeformationCue.perPointResidual(points(baseline, RIGID), points(lm, RIGID),
                points(baseline, CORNERS), points(lm, CORNERS));
        return new Reading(widthRatio(lm) / baselineRatio - 1, (each[0] + each[1]) / 2 / iod, each[0] / iod, each[1] / iod,
                blendshapeSmile(baseline, lm));
    }

    /** One frame during a smile step. */
    public StepStatus observe(long tMs, FaceLandmarks lm) {
        Reading r = read(lm);
        if (onset == null && r.started()) {
            onset = new Onset(lastMs == Long.MIN_VALUE ? tMs : lastMs, tMs);
        }
        lastMs = tMs;
        if (r.smiling()) {
            peak = lm;
            return StepStatus.PASS;
        }
        return StepStatus.IN_PROGRESS;
    }

    /**
     * The deformation cue (Bible Part 8.4) on the baseline and the smiling frame: the smile that proves
     * liveness also unlocks the cue a rigid mask cannot pass (Part 10, cue-unlocking). Abstains before a smile.
     */
    public CueResult deformation(DeformationCue cue) {
        if (peak == null) {
            return CueResult.abstain(DeformationCue.NAME, CueResult.AbstainReason.NOT_ENOUGH_MOTION);
        }
        return cue.evaluate(points(baseline, RIGID), points(peak, RIGID), points(baseline, CORNERS), points(peak, CORNERS),
                iod(baseline));
    }

    /** When the smile began, once it has. */
    public java.util.Optional<Onset> onset() {
        return java.util.Optional.ofNullable(onset);
    }

    static boolean blendshapeSmile(FaceLandmarks baseline, FaceLandmarks lm) {
        if (!baseline.hasBlendshapes() || !lm.hasBlendshapes()) {
            return false;
        }
        for (int i : new int[] {Blendshapes.MOUTH_SMILE_LEFT, Blendshapes.MOUTH_SMILE_RIGHT}) {
            if (lm.blendshape(i) < MIN_BLENDSHAPE || lm.blendshape(i) - baseline.blendshape(i) < MIN_BLENDSHAPE_RISE) {
                return false;
            }
        }
        return true;
    }

    static double iod(FaceLandmarks lm) {
        return lm.dist(FaceLandmarks.RIGHT_IRIS, FaceLandmarks.LEFT_IRIS);
    }

    private static double widthRatio(FaceLandmarks lm) {
        return lm.dist(FaceLandmarks.MOUTH_RIGHT, FaceLandmarks.MOUTH_LEFT) / iod(lm);
    }

    static double[] points(FaceLandmarks lm, int[] idx) {
        double[] out = new double[idx.length * 2];
        for (int i = 0; i < idx.length; i++) {
            out[2 * i] = lm.x(idx[i]);
            out[2 * i + 1] = lm.y(idx[i]);
        }
        return out;
    }
}
