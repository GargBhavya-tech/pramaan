/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.policy.ChallengeKind;

/**
 * What the operator-selected accessibility profiles change (Bible Part 16.1; Build Map #42). Profiles
 * combine: each one only removes kinds or relaxes parameters, never adds difficulty.
 *
 * <table>
 *   <caption>Per profile</caption>
 *   <tr><th>Profile</th><th>Removes</th><th>Relaxes</th></tr>
 *   <tr><td>INFANT</td><td>every motor challenge (flash only)</td><td>-</td></tr>
 *   <tr><td>NO_VISION</td><td>GAZE</td><td>turns guided by audio tones</td></tr>
 *   <tr><td>FACIAL_MOTOR</td><td>SMILE</td><td>one eye closing is enough</td></tr>
 *   <tr><td>LIMITED_MOTION</td><td>-</td><td>15 degree turns only, 150 ms hold, twice the time</td></tr>
 *   <tr><td>FACE_COVERING_CONTEXT</td><td>SMILE (eyes and head pose only)</td><td>-</td></tr>
 * </table>
 *
 * When nothing is left, the plan says so and the session falls through to no-action cues and the
 * multimodal path — never to a reject.
 */
public final class AccessibilityAdjustments {

    public static final int[] DEFAULT_TURN_TARGETS_DEG = {15, 20, 25, 30};
    public static final int[] LIMITED_TURN_TARGETS_DEG = {15};
    public static final int DEFAULT_TURN_HOLD_MS = 300;
    public static final int LIMITED_TURN_HOLD_MS = 150;

    private final Set<AccessibilityProfile> profiles;
    private final Set<ChallengeKind> removed;
    private final int[] turnTargetsDeg;
    private final int turnHoldMs;
    private final int timeoutMultiplier;
    private final boolean eitherEye;
    private final boolean audioGuidedTurn;

    private AccessibilityAdjustments(Set<AccessibilityProfile> profiles, Set<ChallengeKind> removed, int[] turnTargetsDeg,
            int turnHoldMs, int timeoutMultiplier, boolean eitherEye, boolean audioGuidedTurn) {
        this.profiles = Collections.unmodifiableSet(profiles);
        this.removed = Collections.unmodifiableSet(removed);
        this.turnTargetsDeg = turnTargetsDeg;
        this.turnHoldMs = turnHoldMs;
        this.timeoutMultiplier = timeoutMultiplier;
        this.eitherEye = eitherEye;
        this.audioGuidedTurn = audioGuidedTurn;
    }

    public static final AccessibilityAdjustments NONE = of(EnumSet.noneOf(AccessibilityProfile.class));

    public static AccessibilityAdjustments of(Set<AccessibilityProfile> selected) {
        EnumSet<AccessibilityProfile> profiles = selected.isEmpty()
                ? EnumSet.noneOf(AccessibilityProfile.class) : EnumSet.copyOf(selected);
        EnumSet<ChallengeKind> removed = EnumSet.noneOf(ChallengeKind.class);
        int[] targets = DEFAULT_TURN_TARGETS_DEG;
        int hold = DEFAULT_TURN_HOLD_MS;
        int multiplier = 1;
        boolean eitherEye = false;
        boolean audio = false;
        for (AccessibilityProfile p : profiles) {
            switch (p) {
                case INFANT:
                    removed.addAll(EnumSet.complementOf(EnumSet.of(ChallengeKind.FLASH)));
                    break;
                case NO_VISION:
                    removed.add(ChallengeKind.GAZE);
                    audio = true;
                    break;
                case FACIAL_MOTOR:
                    removed.add(ChallengeKind.SMILE);
                    eitherEye = true;
                    break;
                case LIMITED_MOTION:
                    targets = LIMITED_TURN_TARGETS_DEG;
                    hold = LIMITED_TURN_HOLD_MS;
                    multiplier = 2;
                    break;
                case FACE_COVERING_CONTEXT:
                    removed.add(ChallengeKind.SMILE);
                    break;
                default:
                    throw new IllegalStateException("unhandled accessibility profile " + p);
            }
        }
        return new AccessibilityAdjustments(profiles, removed, targets, hold, multiplier, eitherEye, audio);
    }

    public Set<AccessibilityProfile> profiles() {
        return profiles;
    }

    public boolean allows(ChallengeKind kind) {
        return !removed.contains(kind);
    }

    public int[] turnTargetsDeg() {
        return turnTargetsDeg.clone();
    }

    public int turnHoldMs() {
        return turnHoldMs;
    }

    /** Multiplies the policy's step timeout. */
    public int timeoutMultiplier() {
        return timeoutMultiplier;
    }

    /** A single eye closing counts as a blink or eyes-closed (facial palsy). */
    public boolean eitherEye() {
        return eitherEye;
    }

    public boolean audioGuidedTurn() {
        return audioGuidedTurn;
    }
}
