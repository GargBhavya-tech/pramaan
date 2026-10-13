/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.time.Instant;
import java.util.Objects;

/**
 * A capture whose device signature has been verified. The {@code verified*} fields come from inside the
 * signature. Binding (Build Map Phase 8) still has to check them against the session before acceptance.
 */
public final class SignedCapture {

    private final String signedData;
    private final String chainHash;
    private final String previousHash;
    private final String verifiedTransactionId;
    private final String verifiedTimestamp;
    private final String signer;
    private final DeviceMetadata device;
    private final Instant receivedAt;

    public SignedCapture(String signedData, String chainHash, String previousHash, String verifiedTransactionId,
            String verifiedTimestamp, String signer, DeviceMetadata device, Instant receivedAt) {
        this.signedData = Objects.requireNonNull(signedData, "signedData");
        this.chainHash = Objects.requireNonNull(chainHash, "chainHash");
        this.previousHash = Objects.requireNonNull(previousHash, "previousHash");
        this.verifiedTransactionId = Objects.requireNonNull(verifiedTransactionId, "verifiedTransactionId");
        this.verifiedTimestamp = verifiedTimestamp;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.device = device;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** The device-signed compact JWS, exactly as received. */
    public String signedData() {
        return signedData;
    }

    /** The chained hash that travels beside the signature (docs/sbi-notes.md). */
    public String chainHash() {
        return chainHash;
    }

    /** The previousHash this capture was requested with ("" for the first). */
    public String previousHash() {
        return previousHash;
    }

    public String verifiedTransactionId() {
        return verifiedTransactionId;
    }

    public String verifiedTimestamp() {
        return verifiedTimestamp;
    }

    public String signer() {
        return signer;
    }

    public DeviceMetadata device() {
        return device;
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
