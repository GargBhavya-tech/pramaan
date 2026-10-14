/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;

/**
 * Whether the device's {@code digitalId} proves who made it (Bible Part 5, point 1). In MOSIP's SBI a device's
 * digitalId is plain base64 JSON until the device is registered with a device provider, and a JWS signed by that
 * provider afterwards. A signed digitalId whose chain does not reach a trust anchor is a device claiming an identity
 * it cannot prove; an unsigned one is a device that has not been registered. They are different findings.
 */
public enum DeviceIdentity {

    /** Nobody asked (no trust configured, or a test source). Says nothing. */
    UNCHECKED,
    /** Signed, and the certificate chain reaches a configured anchor. */
    VERIFIED,
    /** Not a JWS: an unregistered device. A production station may refuse it by policy. */
    UNSIGNED,
    /** A JWS whose signature or chain does not verify. The device lied, or was tampered with. */
    FAILED;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Judges the raw {@code digitalId} string from device discovery. */
    public static DeviceIdentity check(String digitalId, JwsTrust trust) {
        if (trust == null) {
            return UNCHECKED;
        }
        if (digitalId == null || digitalId.chars().filter(c -> c == '.').count() != 2) {
            return UNSIGNED;
        }
        try {
            String payload = Jws.verify(digitalId, trust).payloadText();
            JsonNode json = JSON.readTree(payload);
            return json.isObject() ? VERIFIED : FAILED;
        } catch (JwsVerificationException | IOException e) {
            return FAILED;
        }
    }
}
