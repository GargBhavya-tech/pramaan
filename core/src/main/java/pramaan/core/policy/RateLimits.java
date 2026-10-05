/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

/** Attempts allowed per rolling hour (Bible Part 12, anti-oracle): every attempt counts, whatever its outcome. */
public final class RateLimits {

    private final int perSubjectPerHour;
    private final int perOperatorPerHour;
    private final int perDevicePerHour;

    RateLimits(int perSubjectPerHour, int perOperatorPerHour, int perDevicePerHour) {
        this.perSubjectPerHour = perSubjectPerHour;
        this.perOperatorPerHour = perOperatorPerHour;
        this.perDevicePerHour = perDevicePerHour;
    }

    static RateLimits read(StrictNode n) {
        int subject = n.integer("perSubjectPerHour");
        int operator = n.integer("perOperatorPerHour");
        int device = n.integer("perDevicePerHour");
        n.finish();
        if (subject < 1 || operator < subject || device < subject) {
            throw new InvalidPolicyException(n.path() + ": need 1 <= perSubjectPerHour <= perOperatorPerHour and perDevicePerHour");
        }
        return new RateLimits(subject, operator, device);
    }

    public int perSubjectPerHour() {
        return perSubjectPerHour;
    }

    public int perOperatorPerHour() {
        return perOperatorPerHour;
    }

    public int perDevicePerHour() {
        return perDevicePerHour;
    }
}
