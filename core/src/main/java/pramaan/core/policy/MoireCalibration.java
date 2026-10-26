/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.util.Objects;

/**
 * How one camera's moire spectrum maps to a probability: the peakiness at which a live face and a screen are equally likely
 * ({@code peakinessThreshold}) and how sharply the vote moves around it ({@code slope}). A camera's own sensor, sharpening and
 * lens set what a live face's spectrum looks like, so the threshold belongs to the camera and lives in the operating-point table,
 * next to every other number the decision rests on, not in code and not in a launch flag. {@code basis} says in words where the
 * numbers came from (a measurement with its sample counts, or "provisional"), so a reader of the table never has to guess.
 */
public final class MoireCalibration {

    /** Whether the cue casts a vote on this camera, or abstains because nothing has shown that it separates live from screen. */
    public enum Mode {
        VOTE,
        ABSTAIN
    }

    /** What the Bible's example ranges suggest and nothing measured: the value of a table that names no camera. */
    public static final double PROVISIONAL_THRESHOLD = 10.0;
    public static final double PROVISIONAL_SLOPE = 0.6;

    private final double peakinessThreshold;
    private final double slope;
    private final Mode mode;
    private final String basis;

    public MoireCalibration(double peakinessThreshold, double slope, Mode mode, String basis) {
        if (!(peakinessThreshold > 0 && peakinessThreshold <= 200)) {
            throw new InvalidPolicyException("moire peakinessThreshold must be in (0, 200]: " + peakinessThreshold);
        }
        if (!(slope > 0 && slope <= 5)) {
            throw new InvalidPolicyException("moire slope must be in (0, 5]: " + slope);
        }
        if (Objects.requireNonNull(basis, "basis").trim().isEmpty()) {
            throw new InvalidPolicyException("a moire calibration must say where its numbers came from (basis)");
        }
        this.peakinessThreshold = peakinessThreshold;
        this.slope = slope;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.basis = basis;
    }

    public double peakinessThreshold() {
        return peakinessThreshold;
    }

    public double slope() {
        return slope;
    }

    public Mode mode() {
        return mode;
    }

    /** True when the cue votes; false when it abstains on this camera. */
    public boolean votes() {
        return mode == Mode.VOTE;
    }

    public String basis() {
        return basis;
    }

    @Override
    public String toString() {
        return mode + ", threshold " + peakinessThreshold + ", slope " + slope + " (" + basis + ")";
    }
}
