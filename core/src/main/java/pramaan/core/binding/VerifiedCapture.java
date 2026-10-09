/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.source.SignedCapture;

/**
 * The fields binding relies on, read only after the binder itself has verified the JWS (Build Map #54):
 * a field is never trusted because some earlier code said the signature was fine.
 */
public final class VerifiedCapture {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SignedCapture capture;
    private final String transactionId;
    private final String timestamp;
    private final byte[] isoRecord;

    private VerifiedCapture(SignedCapture capture, String transactionId, String timestamp, byte[] isoRecord) {
        this.capture = capture;
        this.transactionId = transactionId;
        this.timestamp = timestamp;
        this.isoRecord = isoRecord;
    }

    /** Verifies the signature against the device trust, then reads the payload; throws if either fails. */
    public static VerifiedCapture verify(SignedCapture capture, JwsTrust trust) throws JwsVerificationException, IOException {
        String payload = Jws.verify(capture.signedData(), trust).payloadText();
        JsonNode p = JSON.readTree(payload);
        String bio = p.path("bioValue").asText("");
        byte[] iso = bio.isEmpty() ? new byte[0] : Base64.getUrlDecoder().decode(bio.replace("=", ""));
        return new VerifiedCapture(capture, p.path("transactionId").asText(""), p.path("timestamp").asText(""), iso);
    }

    public SignedCapture capture() {
        return capture;
    }

    public String transactionId() {
        return transactionId;
    }

    public String timestamp() {
        return timestamp;
    }

    /** The signed ISO 19794-5 record. */
    public byte[] isoRecord() {
        return isoRecord.clone();
    }
}
