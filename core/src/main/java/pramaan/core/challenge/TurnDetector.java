/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Objects;
import java.util.Optional;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.FlatnessCue;

/**
 * Verifies a head turn (Bible Part 11.3; Build Map #45) on yaw relative to the resident's frontal
 * baseline: reach 75% of the commanded angle, hold it, then come back towards centre.
 * Turning the wrong way by 10 degrees or more is a wrong action.
 *
 * <p>Side effect: the frontal frame and the most-turned frame are exactly what the flatness cue (Part 8.3)
 * needs, so {@link #flatness} turns the challenge into PAD evidence as well — the action that proves
 * liveness also unlocks a cue.
 */
public final class TurnDetector {

    /**
     * Pass once the turn reaches this fraction of the commanded angle. Generous on purpose: yaw estimates
     * carry per-person error, and the exact angle buys no security. A turn past the target satisfies every
     * smaller target, so only the direction is a verifiable secret (Bible 47.8).
     */
    public static final double PASS_FRACTION = 0.75;
    public static final double ONSET_DEG = 5;
    public static final double WRONG_WAY_DEG = 10;
    /** Unrequested turn this large during a non-turn step is a wrong action. */
    public static final double UNREQUESTED_TURN_DEG = 20;

    private final Challenge challenge;
    private final long holdMs;
    private final double baselineYaw;
    private final FaceLandmarks frontal;
    private Onset onset;
    private long holdStartMs = -1;
    private boolean held;
    private StepStatus status = StepStatus.IN_PROGRESS;
    private FaceLandmarks peak;
    private double peakYaw;
    private long lastMs = Long.MIN_VALUE;

    /**
     * @param baselineYaw the frontal yaw from the passive window; 0 when the caller passes
     *     {@link HeadPoseEstimator#relativeYawDegrees} values, which are already relative
     * @param frontal a frontal-window frame's landmarks, for the flatness side effect (may be null)
     */
    public TurnDetector(Challenge challenge, long holdMs, double baselineYaw, FaceLandmarks frontal) {
        if (challenge.kind() != pramaan.core.policy.ChallengeKind.TURN) {
            throw new IllegalArgumentException("not a turn challenge: " + challenge);
        }
        this.challenge = challenge;
        this.holdMs = holdMs;
        this.baselineYaw = baselineYaw;
        this.frontal = frontal;
    }

    public StepStatus observe(long tMs, double yawDeg, FaceLandmarks lm) {
        if (status != StepStatus.IN_PROGRESS) {
            return status;
        }
        double toward = challenge.direction().yawSign() * (yawDeg - baselineYaw);
        if (onset == null && Math.abs(yawDeg - baselineYaw) >= ONSET_DEG) {
            onset = new Onset(lastMs == Long.MIN_VALUE ? tMs : lastMs, tMs);
        }
        if (toward <= -WRONG_WAY_DEG) {
            status = StepStatus.WRONG_ACTION;
            return status;
        }
        if (toward > peakYaw) {
            peakYaw = toward;
            peak = lm;
        }
        if (toward >= PASS_FRACTION * challenge.targetDeg()) {
            if (holdStartMs < 0) {
                holdStartMs = tMs;
            }
            if (tMs - holdStartMs >= holdMs) {
                held = true;
            }
        } else {
            holdStartMs = -1;
            if (held && toward <= challenge.targetDeg() / 2.0) {
                status = StepStatus.PASS;
            }
        }
        lastMs = tMs;
        return status;
    }

    public Optional<Onset> onset() {
        return Optional.ofNullable(onset);
    }

    /** How far towards the commanded side the head got, relative to baseline. */
    public double peakYawDeg() {
        return peakYaw;
    }

    /** The flatness cue on (frontal, most-turned) landmarks; abstains without both frames or enough motion. */
    public CueResult flatness(FlatnessCue cue) {
        if (frontal == null || peak == null) {
            return CueResult.abstain(FlatnessCue.NAME, CueResult.AbstainReason.NOT_ENOUGH_MOTION);
        }
        int n = 468;
        double[] p0 = new double[2 * n];
        double[] p1 = new double[2 * n];
        for (int i = 0; i < n; i++) {
            p0[2 * i] = frontal.x(i);
            p0[2 * i + 1] = frontal.y(i);
            p1[2 * i] = peak.x(i);
            p1[2 * i + 1] = peak.y(i);
        }
        return cue.evaluate(p0, p1, peakYaw, SmileDetector.iod(frontal));
    }

    /** During a step that is not a turn: a big turn is a deliberate, different action. Blinks and smiles never are. */
    public static boolean unrequestedTurn(double yawDeg, double baselineYaw) {
        return Math.abs(yawDeg - baselineYaw) >= UNREQUESTED_TURN_DEG;
    }

    public Challenge challenge() {
        return Objects.requireNonNull(challenge);
    }
}
