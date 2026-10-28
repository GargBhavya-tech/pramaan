/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.clientmanager.dto.registration;

public class BiometricsDto {

    private String payLoad;
    private String signature;
    private String specVersion;
    private float qualityScore;
    private boolean isForceCaptured;
    private int numOfRetries;
    private double sdkScore;
    /** Signed liveness evidence (a JWS) for a face capture; null when no hook ran. */
    private String livenessEvidence;
    /** The device's raw rCapture response element, kept so a liveness hook can verify it. */
    private String sbiResponse;
    private boolean captured;
    private String modalityName;

    public float getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(float qualityScore) {
        this.qualityScore = qualityScore;
    }

    public String getPayLoad() {
        return payLoad;
    }

    public void setPayLoad(String payLoad) {
        this.payLoad = payLoad;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSpecVersion() {
        return specVersion;
    }

    public void setSpecVersion(String specVersion) {
        this.specVersion = specVersion;
    }

    public String getLivenessEvidence() {
        return livenessEvidence;
    }

    public void setLivenessEvidence(String livenessEvidence) {
        this.livenessEvidence = livenessEvidence;
    }

    public String getSbiResponse() {
        return sbiResponse;
    }

    public void setSbiResponse(String sbiResponse) {
        this.sbiResponse = sbiResponse;
    }

    public boolean isCaptured() {
        return captured;
    }

    public void setCaptured(boolean captured) {
        this.captured = captured;
    }

    public String getModalityName() {
        return modalityName;
    }

    public void setModalityName(String modalityName) {
        this.modalityName = modalityName;
    }
}
