/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

/** MOSIP device assurance level (Bible Part 1.2): where the signing key lives. */
public enum DeviceLevel {
    /** Key in host software. Allowed for supervised registration only. */
    L0,
    /** Key in the device's secure element (FTM). Allowed everywhere. */
    L1;

    /** Parses "L0"/"L1" as reported by SBI discovery; anything else is treated as the weaker L0. */
    public static DeviceLevel fromSbi(String certification) {
        return "L1".equalsIgnoreCase(certification == null ? "" : certification.trim()) ? L1 : L0;
    }
}
