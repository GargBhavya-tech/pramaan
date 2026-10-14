/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import pramaan.core.source.CaptureRequest;

/**
 * Builds SBI request bodies. Every scalar is written as a JSON <strong>string</strong>: MOSIP's reference
 * device service rejects numbers ("timeout must be a string, not a number"; docs/sbi-notes.md).
 */
public final class SbiRequests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SbiRequests() {
    }

    public static byte[] discovery() {
        ObjectNode body = JSON.createObjectNode();
        body.put("type", "Biometric Device");
        return bytes(body);
    }

    public static byte[] stream(String deviceId, String deviceSubId, long timeoutMillis) {
        ObjectNode body = JSON.createObjectNode();
        body.put("deviceId", deviceId);
        body.put("deviceSubId", deviceSubId);
        body.put("timeout", Long.toString(timeoutMillis));
        return bytes(body);
    }

    /**
     * A stream request for a device that offers the SBI Liveness Profile (Bible Part 41): the usual fields plus the
     * transaction id and the client's ephemeral key, every scalar a string (docs/schemas/sbi-liveness-profile.schema.json,
     * {@code KeyAgreementRequest}).
     */
    public static byte[] streamWithProfile(String deviceId, String deviceSubId, long timeoutMillis, String transactionId,
            String clientEphemeralKeyBase64) {
        ObjectNode body = JSON.createObjectNode();
        body.put("deviceId", deviceId);
        body.put("deviceSubId", deviceSubId);
        body.put("timeout", Long.toString(timeoutMillis));
        ObjectNode profile = body.putObject("livenessProfile");
        profile.put("version", "0.1");
        profile.put("transactionId", transactionId);
        profile.put("clientEphemeralKey", clientEphemeralKeyBase64);
        return bytes(body);
    }

    /** {@code captureBurst} (Liveness Profile, addition 4): several signed frames during a challenge. */
    public static byte[] captureBurst(String transactionId, int count, long intervalMillis) {
        ObjectNode body = JSON.createObjectNode();
        body.put("transactionId", transactionId);
        body.put("count", Integer.toString(count));
        body.put("intervalMs", Long.toString(intervalMillis));
        return bytes(body);
    }

    /** {@code illuminate} (Liveness Profile, addition 5): the client's random schedule, every scalar a string. */
    public static byte[] illuminate(String transactionId, pramaan.core.challenge.FlashSequence sequence) {
        ObjectNode body = JSON.createObjectNode();
        body.put("transactionId", transactionId);
        ArrayNode schedule = body.putArray("schedule");
        for (int i = 0; i < sequence.colours().size(); i++) {
            ObjectNode step = schedule.addObject();
            step.put("colour", sequence.colours().get(i).name());
            step.put("durationMs", Integer.toString(sequence.durationsMs()[i]));
        }
        return bytes(body);
    }

    public static byte[] rcaptureFace(CaptureRequest request, String deviceId, String deviceSubId, String env,
            String specVersion, Clock clock) {
        ObjectNode body = JSON.createObjectNode();
        body.put("env", env);
        body.put("purpose", "Registration");
        body.put("specVersion", specVersion);
        body.put("timeout", Long.toString(request.timeout().toMillis()));
        body.put("captureTime", DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        body.put("transactionId", request.transactionId());
        ArrayNode bio = body.putArray("bio");
        ObjectNode face = bio.addObject();
        face.put("type", "Face");
        face.put("count", "1");
        face.putArray("bioSubType");
        face.putArray("exception");
        face.put("requestedScore", Integer.toString(request.requestedScore()));
        face.put("deviceId", deviceId);
        face.put("deviceSubId", deviceSubId);
        face.put("previousHash", request.previousHash());
        body.putNull("customOpts");
        return bytes(body);
    }

    private static byte[] bytes(ObjectNode body) {
        try {
            return JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise an SBI request", e);
        }
    }
}
