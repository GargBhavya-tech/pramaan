/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import pramaan.core.landmarks.Blendshapes;
import pramaan.core.landmarks.FaceLandmarks;

/**
 * Blink and eyes-closed detection with a per-person, per-eye threshold (Bible Part 11.1; Build Map #43):
 * {@code theta = 0.7 x median EAR} over the passive window. A fixed threshold (0.2 is common) reads people
 * with naturally narrow eyes or ptosis as permanently blinking; the adaptive baseline is the inclusion fix.
 *
 * <p>Where blendshapes are computed, {@code eyeBlinkLeft/Right} are a second vote: the eyes also count as closed when
 * the blink scores (both, or either for FACIAL_MOTOR) are at least {@value #BLINK_BLENDSHAPE} and have risen
 * {@value #BLINK_BLENDSHAPE_RISE} over the person's own median in the passive window. The rise keeps the inclusion
 * fix: a resident whose resting eyeBlink is already high (narrow eyes, ptosis) is not read as permanently closed.
 *
 * <p>Durations are measured in time, not frames, so the same detector works at 30 fps and at 3 fps (where
 * only {@link EyeEvent.Type#CLOSED_HOLD} is requested — a 100-400 ms blink falls between frames).
 */
public final class EyeStateDetector {

    public static final double THETA_FRACTION = 0.7;
    public static final int MIN_BASELINE_SAMPLES = 5;
    public static final long BLINK_MIN_MS = 50;
    public static final long BLINK_MAX_MS = 500;
    public static final double BLINK_BLENDSHAPE = 0.5;
    public static final double BLINK_BLENDSHAPE_RISE = 0.3;

    private final List<Double> baselineRight = new ArrayList<>();
    private final List<Double> baselineLeft = new ArrayList<>();
    private final List<Double> baselineBlinkA = new ArrayList<>();
    private final List<Double> baselineBlinkB = new ArrayList<>();
    private final boolean eitherEye;
    private final long holdMs;
    private double thetaRight = Double.NaN;
    private double thetaLeft = Double.NaN;
    private boolean closed;
    private boolean holdReported;
    private long closedSinceMs;
    private long lastOpenMs = Long.MIN_VALUE;
    private long lastMs = Long.MIN_VALUE;

    /**
     * @param eitherEye one eye closing is enough (FACIAL_MOTOR profile)
     * @param holdMs the eyes-closed duration that completes {@link EyeEvent.Type#CLOSED_HOLD}
     */
    public EyeStateDetector(boolean eitherEye, long holdMs) {
        this.eitherEye = eitherEye;
        this.holdMs = holdMs;
    }

    /** One passive-window sample, eyes presumably open. */
    public void addBaseline(double earRight, double earLeft) {
        baselineRight.add(earRight);
        baselineLeft.add(earLeft);
        thetaRight = THETA_FRACTION * median(baselineRight);
        thetaLeft = THETA_FRACTION * median(baselineLeft);
    }

    public void addBaseline(FaceLandmarks lm) {
        addBaseline(lm.eyeAspectRatio(true), lm.eyeAspectRatio(false),
                lm.blendshape(Blendshapes.EYE_BLINK_LEFT), lm.blendshape(Blendshapes.EYE_BLINK_RIGHT));
    }

    /** As {@link #addBaseline(double, double)} with the eyeBlinkLeft/Right scores; NaN when there are none. */
    public void addBaseline(double earRight, double earLeft, double blinkLeft, double blinkRight) {
        addBaseline(earRight, earLeft);
        if (!Double.isNaN(blinkLeft) && !Double.isNaN(blinkRight)) {
            baselineBlinkA.add(blinkLeft);
            baselineBlinkB.add(blinkRight);
        }
    }

    public boolean ready() {
        return baselineRight.size() >= MIN_BASELINE_SAMPLES;
    }

    public double threshold(boolean rightEye) {
        return rightEye ? thetaRight : thetaLeft;
    }

    public Optional<EyeEvent> observe(long tMs, FaceLandmarks lm) {
        return observe(tMs, lm.eyeAspectRatio(true), lm.eyeAspectRatio(false), blendshapeClosed(lm));
    }

    /** One frame during a challenge. Returns an event when a blink or hold completes on this frame. */
    public Optional<EyeEvent> observe(long tMs, double earRight, double earLeft) {
        return observe(tMs, earRight, earLeft, false);
    }

    /** The blendshape vote; false without blendshapes or without a blendshape baseline. */
    boolean blendshapeClosed(FaceLandmarks lm) {
        if (!lm.hasBlendshapes() || baselineBlinkA.size() < MIN_BASELINE_SAMPLES) {
            return false;
        }
        boolean a = blinkVote(lm.blendshape(Blendshapes.EYE_BLINK_LEFT), median(baselineBlinkA));
        boolean b = blinkVote(lm.blendshape(Blendshapes.EYE_BLINK_RIGHT), median(baselineBlinkB));
        return eitherEye ? (a || b) : (a && b);
    }

    private static boolean blinkVote(double score, double baseline) {
        return score >= BLINK_BLENDSHAPE && score - baseline >= BLINK_BLENDSHAPE_RISE;
    }

    private Optional<EyeEvent> observe(long tMs, double earRight, double earLeft, boolean blendshapeClosed) {
        if (!ready()) {
            throw new IllegalStateException("no EAR baseline yet: need " + MIN_BASELINE_SAMPLES + " passive-window samples");
        }
        if (tMs < lastMs) {
            throw new IllegalArgumentException("frames must arrive in time order");
        }
        boolean r = earRight < thetaRight;
        boolean l = earLeft < thetaLeft;
        boolean nowClosed = (eitherEye ? (r || l) : (r && l)) || blendshapeClosed;
        Optional<EyeEvent> event = Optional.empty();
        if (nowClosed && !closed) {
            closed = true;
            holdReported = false;
            closedSinceMs = tMs;
            lastOpenMs = lastMs == Long.MIN_VALUE ? tMs : lastMs;
        }
        if (nowClosed && !holdReported) {
            // The eyes closed somewhere between the last open frame and the first closed one; the midpoint is
            // the unbiased estimate. The first closed frame alone would make a real 1 s hold at 3 fps read 999 ms.
            long closedFor = tMs - (lastOpenMs + closedSinceMs) / 2;
            if (closedFor >= holdMs) {
                holdReported = true;
                event = Optional.of(new EyeEvent(EyeEvent.Type.CLOSED_HOLD, new Onset(lastOpenMs, closedSinceMs), closedFor));
            }
        }
        if (!nowClosed && closed) {
            closed = false;
            long duration = tMs - closedSinceMs;
            if (!holdReported && duration >= BLINK_MIN_MS && duration <= BLINK_MAX_MS) {
                event = Optional.of(new EyeEvent(EyeEvent.Type.BLINK, new Onset(lastOpenMs, closedSinceMs), duration));
            }
        }
        lastMs = tMs;
        return event;
    }

    private static double median(List<Double> values) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(v);
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    }
}
