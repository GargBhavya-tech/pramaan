/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.List;
import java.util.Optional;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.EyeEvent;
import pramaan.core.challenge.EyeStateDetector;
import pramaan.core.challenge.HeadPoseEstimator;
import pramaan.core.challenge.Onset;
import pramaan.core.challenge.SmileDetector;
import pramaan.core.challenge.StepStatus;
import pramaan.core.challenge.TurnDetector;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.DeformationCue;
import pramaan.core.pad.FlatnessCue;
import pramaan.core.policy.ChallengeKind;

/**
 * One challenge step, from the frame the prompt was shown on (Bible Part 11). That frame is observed first,
 * so an action already under way when the prompt appeared has its onset before the prompt, and the latency
 * rule calls it early — the pre-timed replay the random onset delay is there to catch.
 *
 * <p>Yaw is relative to the passive window's frontal landmarks, and the baseline is the head's yaw at the
 * prompt: a resident who drifted a few degrees before the prompt is not credited or blamed for the drift.
 * During a step that is not a turn, a large turn is a wrong action; blinks and smiles never are.
 */
final class StepVerifier {

    /** Kinds this verifier can check. GAZE and FLASH need a resident-facing screen and are not built yet. */
    static final List<ChallengeKind> VERIFIABLE = List.of(ChallengeKind.TURN, ChallengeKind.BLINK,
            ChallengeKind.EYES_CLOSED, ChallengeKind.SMILE);

    private final Challenge challenge;
    private final FaceLandmarks frontal;
    private final double baselineYaw;
    private final TurnDetector turn;
    private final EyeStateDetector eyes;
    private final SmileDetector smile;
    private int blinks;
    private Onset eyeOnset;
    private StepStatus status = StepStatus.IN_PROGRESS;

    /**
     * @param earBaseline eye aspect ratios (right, left) and eyeBlink scores (left, right; NaN without blendshapes)
     *     from open-eyed frames of this attempt; at least
     *     {@link EyeStateDetector#MIN_BASELINE_SAMPLES} for an eye step — see {@link #ready}
     */
    StepVerifier(Challenge challenge, AccessibilityAdjustments adjustments, FaceLandmarks frontal,
            List<double[]> earBaseline, long promptMs, FaceLandmarks atPrompt) {
        this.challenge = challenge;
        this.frontal = frontal;
        this.baselineYaw = HeadPoseEstimator.relativeYawDegrees(frontal, atPrompt);
        TurnDetector t = null;
        EyeStateDetector e = null;
        SmileDetector s = null;
        switch (challenge.kind()) {
            case TURN:
                t = new TurnDetector(challenge, adjustments.turnHoldMs(), baselineYaw, frontal);
                break;
            case BLINK:
            case EYES_CLOSED:
                e = new EyeStateDetector(adjustments.eitherEye(),
                        challenge.kind() == ChallengeKind.EYES_CLOSED ? challenge.holdMs() : Long.MAX_VALUE);
                for (double[] ear : earBaseline) {
                    e.addBaseline(ear[0], ear[1], ear[2], ear[3]);
                }
                break;
            case SMILE:
                s = new SmileDetector(frontal);
                break;
            default:
                throw new IllegalArgumentException("no verifier for " + challenge.kind());
        }
        this.turn = t;
        this.eyes = e;
        this.smile = s;
        observe(promptMs, atPrompt);
    }

    /**
     * Whether the face is back at rest — near frontal, eyes open, not smiling — so a prompt shown now meets a
     * face that is not still finishing the previous step. Without this, eyes still closed from an eyes-closed
     * step read as an early blink, and a head swinging back from a turn as an early or wrong-way turn.
     */
    static boolean atRest(FaceLandmarks frontal, FaceLandmarks lm, List<double[]> earBaseline) {
        if (Math.abs(HeadPoseEstimator.relativeYawDegrees(frontal, lm)) >= TurnDetector.ONSET_DEG) {
            return false;
        }
        if (new SmileDetector(frontal).read(lm).widthGain() >= SmileDetector.MIN_WIDTH_GAIN / 2) {
            return false;
        }
        if (earBaseline.size() >= EyeStateDetector.MIN_BASELINE_SAMPLES) {
            double right = lm.eyeAspectRatio(true);
            double left = lm.eyeAspectRatio(false);
            return right >= EyeStateDetector.THETA_FRACTION * median(earBaseline, 0)
                    && left >= EyeStateDetector.THETA_FRACTION * median(earBaseline, 1);
        }
        return true;
    }

    private static double median(List<double[]> samples, int eye) {
        double[] v = new double[samples.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = samples.get(i)[eye];
        }
        java.util.Arrays.sort(v);
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    }

    /** Whether a step of this kind can be verified with the baseline gathered so far. */
    static boolean ready(Challenge challenge, int earBaselineSamples) {
        boolean eyeStep = challenge.kind() == ChallengeKind.BLINK || challenge.kind() == ChallengeKind.EYES_CLOSED;
        return !eyeStep || earBaselineSamples >= EyeStateDetector.MIN_BASELINE_SAMPLES;
    }

    StepStatus observe(long tMs, FaceLandmarks lm) {
        if (status != StepStatus.IN_PROGRESS) {
            return status;
        }
        double yaw = HeadPoseEstimator.relativeYawDegrees(frontal, lm);
        if (turn != null) {
            status = turn.observe(tMs, yaw, lm);
            return status;
        }
        if (TurnDetector.unrequestedTurn(yaw, baselineYaw)) {
            status = StepStatus.WRONG_ACTION;
            return status;
        }
        if (smile != null) {
            status = smile.observe(tMs, lm);
            return status;
        }
        Optional<EyeEvent> event = eyes.observe(tMs, lm);
        if (event.isPresent()) {
            EyeEvent ev = event.get();
            if (challenge.kind() == ChallengeKind.EYES_CLOSED && ev.type() == EyeEvent.Type.CLOSED_HOLD) {
                eyeOnset = ev.onset();
                status = StepStatus.PASS;
            } else if (challenge.kind() == ChallengeKind.BLINK && ev.type() == EyeEvent.Type.BLINK) {
                if (blinks++ == 0) {
                    eyeOnset = ev.onset();
                }
                if (blinks >= challenge.count()) {
                    status = StepStatus.PASS;
                }
            }
        }
        return status;
    }

    /** When the action began; present once the step has passed. */
    Optional<Onset> onset() {
        if (turn != null) {
            return turn.onset();
        }
        if (smile != null) {
            return smile.onset();
        }
        return Optional.ofNullable(eyeOnset);
    }

    /**
     * The cue this step unlocked (Bible Part 10): flatness from a turn's frontal and most-turned frames
     * (Part 8.3), deformation from a smile (Part 8.4); null for steps that unlock nothing.
     */
    CueResult unlockedCue() {
        if (turn != null) {
            return turn.flatness(new FlatnessCue());
        }
        return smile == null ? null : smile.deformation(new DeformationCue());
    }

    Challenge challenge() {
        return challenge;
    }
}
