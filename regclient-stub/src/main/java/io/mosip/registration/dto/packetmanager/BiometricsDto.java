/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.dto.packetmanager;

/**
 * A compile-time stand-in for the Registration Client's {@code BiometricsDto}: only the two fields the PRAMAAN hook reads
 * and writes (both added by the patch). The real class has many more. Never shipped.
 */
public class BiometricsDto {

    private String sbiResponse;
    private String livenessEvidence;

    public String getSbiResponse() {
        return sbiResponse;
    }

    public void setSbiResponse(String sbiResponse) {
        this.sbiResponse = sbiResponse;
    }

    public String getLivenessEvidence() {
        return livenessEvidence;
    }

    public void setLivenessEvidence(String livenessEvidence) {
        this.livenessEvidence = livenessEvidence;
    }
}
