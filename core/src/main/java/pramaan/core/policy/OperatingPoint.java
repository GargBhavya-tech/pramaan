/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

/** A named pair of thresholds on P(live), with the target they were chosen to meet (Bible Part 9). */
public final class OperatingPoint {

    private final String name;
    private final double tauAccept;
    private final double tauReject;
    private final String target;

    public OperatingPoint(String name, double tauAccept, double tauReject, String target) {
        if (!(tauReject > 0 && tauReject < tauAccept && tauAccept < 1)) {
            throw new InvalidPolicyException(name + ": need 0 < tauReject < tauAccept < 1, got tauReject="
                    + tauReject + " tauAccept=" + tauAccept);
        }
        this.name = name;
        this.tauAccept = tauAccept;
        this.tauReject = tauReject;
        this.target = target;
    }

    public String name() {
        return name;
    }

    public double tauAccept() {
        return tauAccept;
    }

    public double tauReject() {
        return tauReject;
    }

    /** What the thresholds are meant to achieve, e.g. "BPCER <= 3% at APCER <= 5%". */
    public String target() {
        return target;
    }
}
