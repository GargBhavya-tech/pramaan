/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Objects;
import pramaan.core.policy.ChallengeKind;

/**
 * One step of a challenge sequence with its parameters (Bible Part 10). Parameters that do not apply to
 * the kind are 0 / null. {@link #onsetDelayMs()} is the random pause before the prompt is shown.
 */
public final class Challenge {

    private final ChallengeKind kind;
    private final TurnDirection direction;
    private final int targetDeg;
    private final int count;
    private final int holdMs;
    private final int gazeCell;
    private final int onsetDelayMs;

    private Challenge(ChallengeKind kind, TurnDirection direction, int targetDeg, int count, int holdMs, int gazeCell,
            int onsetDelayMs) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.direction = direction;
        this.targetDeg = targetDeg;
        this.count = count;
        this.holdMs = holdMs;
        this.gazeCell = gazeCell;
        this.onsetDelayMs = onsetDelayMs;
    }

    public static Challenge turn(TurnDirection direction, int targetDeg) {
        return new Challenge(ChallengeKind.TURN, Objects.requireNonNull(direction, "direction"), targetDeg, 0, 0, 0, 0);
    }

    public static Challenge blink(int count) {
        return new Challenge(ChallengeKind.BLINK, null, 0, count, 0, 0, 0);
    }

    public static Challenge eyesClosed(int holdMs) {
        return new Challenge(ChallengeKind.EYES_CLOSED, null, 0, 0, holdMs, 0, 0);
    }

    public static Challenge smile() {
        return new Challenge(ChallengeKind.SMILE, null, 0, 0, 0, 0, 0);
    }

    /** @param cell 0-8, row-major on a 3x3 grid */
    public static Challenge gaze(int cell) {
        if (cell < 0 || cell > 8) {
            throw new IllegalArgumentException("gaze cell must be 0-8, got " + cell);
        }
        return new Challenge(ChallengeKind.GAZE, null, 0, 0, 0, cell, 0);
    }

    public Challenge withOnsetDelay(int ms) {
        return new Challenge(kind, direction, targetDeg, count, holdMs, gazeCell, ms);
    }

    public ChallengeKind kind() {
        return kind;
    }

    public TurnDirection direction() {
        return direction;
    }

    public int targetDeg() {
        return targetDeg;
    }

    public int count() {
        return count;
    }

    public int holdMs() {
        return holdMs;
    }

    public int gazeCell() {
        return gazeCell;
    }

    public int onsetDelayMs() {
        return onsetDelayMs;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Challenge)) {
            return false;
        }
        Challenge c = (Challenge) o;
        return kind == c.kind && direction == c.direction && targetDeg == c.targetDeg && count == c.count
                && holdMs == c.holdMs && gazeCell == c.gazeCell && onsetDelayMs == c.onsetDelayMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, direction, targetDeg, count, holdMs, gazeCell, onsetDelayMs);
    }

    @Override
    public String toString() {
        switch (kind) {
            case TURN:
                return "TURN " + direction + " " + targetDeg + "deg +" + onsetDelayMs + "ms";
            case BLINK:
                return "BLINK x" + count + " +" + onsetDelayMs + "ms";
            case EYES_CLOSED:
                return "EYES_CLOSED " + holdMs + "ms +" + onsetDelayMs + "ms";
            case GAZE:
                return "GAZE cell " + gazeCell + " +" + onsetDelayMs + "ms";
            default:
                return kind + " +" + onsetDelayMs + "ms";
        }
    }
}
