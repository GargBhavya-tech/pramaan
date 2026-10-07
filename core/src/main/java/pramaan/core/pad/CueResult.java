/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.Objects;

/**
 * What one cue says: a calibrated probability that the face is live, or {@code ABSTAIN} with a reason.
 * Abstention is never coerced to 0.5 or to "spoof" — a missing capability is not evidence (Bible Part 8).
 */
public final class CueResult {

    /** Why a cue did not vote. */
    public enum AbstainReason {
        CAPABILITY_MISSING,
        FRAME_RATE_TOO_LOW,
        NOT_ENOUGH_MOTION,
        UNTRAINED,
        INPUT_TOO_SMALL,
        NOT_APPLICABLE
    }

    private final String cue;
    private final double pLive;
    private final double rawValue;
    private final AbstainReason abstain;

    private CueResult(String cue, double pLive, double rawValue, AbstainReason abstain) {
        this.cue = Objects.requireNonNull(cue, "cue");
        this.pLive = pLive;
        this.rawValue = rawValue;
        this.abstain = abstain;
    }

    public static CueResult vote(String cue, double pLive, double rawValue) {
        if (!(pLive > 0 && pLive < 1)) {
            throw new IllegalArgumentException("a vote must be a probability strictly between 0 and 1, got " + pLive);
        }
        return new CueResult(cue, pLive, rawValue, null);
    }

    public static CueResult abstain(String cue, AbstainReason reason) {
        return new CueResult(cue, Double.NaN, Double.NaN, Objects.requireNonNull(reason, "reason"));
    }

    public String cue() {
        return cue;
    }

    public boolean abstained() {
        return abstain != null;
    }

    public AbstainReason abstainReason() {
        return abstain;
    }

    /** Probability live; NaN when abstained. */
    public double pLive() {
        return pLive;
    }

    /** The cue's own measurement (peakiness, residual...), for diagnostics; NaN when abstained. */
    public double rawValue() {
        return rawValue;
    }
}
