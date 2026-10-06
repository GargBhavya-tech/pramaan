/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi.capture;

import pramaan.core.crypto.UnverifiedJws;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An SBI {@code RCAPTURE} response (MDS specification 1.2.0; Bible Part 1.2, Build Map #4).
 *
 * <p>Shape: {@code {"biometrics": [ {"specVersion", "data": <JWS>, "hash", ...} ], ...}}. Each
 * entry's {@code data} is a device-signed JWS whose payload carries {@code transactionId},
 * {@code timestamp}, {@code digitalId}, {@code bioValue}, {@code qualityScore} and more. The chained
 * {@code hash} sits <em>beside</em> the JWS, not inside it: a verifier recomputes it from the signed
 * {@code bioValue} and the previous hash (Build Map #55).
 *
 * <p>Parsing does not verify anything. Payload accessors are therefore prefixed {@code unverified}.
 */
public final class RCaptureResponse {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Entry> entries;

    private RCaptureResponse(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public static RCaptureResponse parse(byte[] json) throws IOException {
        Objects.requireNonNull(json, "json");
        JsonNode root = JSON.readTree(json);
        JsonNode biometrics = root.path("biometrics");
        if (!biometrics.isArray() || biometrics.isEmpty()) {
            throw new IOException("rcapture response has no 'biometrics' array");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : biometrics) {
            String data = node.path("data").asText("");
            if (data.isEmpty()) {
                throw new IOException("biometrics entry without 'data'; error: " + node.path("error"));
            }
            UnverifiedJws jws = UnverifiedJws.parse(data);
            JsonNode payload = JSON.readTree(jws.payloadText());
            entries.add(new Entry(node.path("specVersion").asText(null), jws, payload, node.path("hash").asText(null)));
        }
        return new RCaptureResponse(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** One captured biometric: the device-signed JWS plus the chained hash that travels beside it. */
    public static final class Entry {

        private final String specVersion;
        private final UnverifiedJws data;
        private final JsonNode payload;
        private final String hash;

        Entry(String specVersion, UnverifiedJws data, JsonNode payload, String hash) {
            this.specVersion = specVersion;
            this.data = data;
            this.payload = payload;
            this.hash = hash;
        }

        public String specVersion() {
            return specVersion;
        }

        /** The signed data. Verify it (Build Map #54) before trusting anything in it. */
        public UnverifiedJws data() {
            return data;
        }

        /** Chained capture hash, outside the signature (upper-case hex SHA-256 per the spec). */
        public String hash() {
            return hash;
        }

        public String unverifiedTransactionId() {
            return text("transactionId");
        }

        public String unverifiedTimestamp() {
            return text("timestamp");
        }

        public String unverifiedBioType() {
            return text("bioType");
        }

        public String unverifiedPurpose() {
            return text("purpose");
        }

        public String unverifiedQualityScore() {
            return text("qualityScore");
        }

        public boolean payloadHas(String field) {
            return payload.has(field);
        }

        private String text(String field) {
            JsonNode value = payload.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }
    }
}
