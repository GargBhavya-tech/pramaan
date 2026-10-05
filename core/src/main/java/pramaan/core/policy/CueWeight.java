/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

/**
 * One cue's fusion weights (Bible Part 9, asymmetric cues). {@code wPos} multiplies evidence of life
 * (logit >= 0), {@code wNeg} evidence of attack (logit < 0). A physiological cue's {@code wNeg} is ignored
 * for residents: fusion forces it to zero, whatever this table says.
 */
public final class CueWeight {

    private final String cue;
    private final double wPos;
    private final double wNeg;
    private final boolean physiological;

    public CueWeight(String cue, double wPos, double wNeg, boolean physiological) {
        if (!(wPos >= 0 && wNeg >= 0 && Double.isFinite(wPos) && Double.isFinite(wNeg))) {
            throw new InvalidPolicyException(cue + ": weights must be finite and >= 0");
        }
        this.cue = cue;
        this.wPos = wPos;
        this.wNeg = wNeg;
        this.physiological = physiological;
    }

    public String cue() {
        return cue;
    }

    public double wPos() {
        return wPos;
    }

    public double wNeg() {
        return wNeg;
    }

    public boolean physiological() {
        return physiological;
    }
}
