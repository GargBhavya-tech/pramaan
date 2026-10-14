/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.binding.BindingLevel;
import pramaan.core.binding.LockOutcome;
import pramaan.core.binding.SignedStreamLock;
import pramaan.core.binding.StreamKeyAgreement;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.testing.TestClock;

/**
 * Build Map #100, Bible Part 41: MockDeviceProfileConformanceTest. The mock device is the profile's reference
 * implementation, so everything it emits must satisfy {@code docs/schemas/sbi-liveness-profile.schema.json}, and a
 * client running {@link SignedStreamLock} against it must reach {@code CRYPTOGRAPHIC} binding — and must not when a
 * frame is injected or the capture names another stream.
 */
class MockDeviceProfileConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SCHEMA = Path.of("..", "docs", "schemas", "sbi-liveness-profile.schema.json");
    private static final String KID = "mock-device";
    private static final String TXN = "0123456789abcdef0123456789abcdef";

    private static JsonNode schema;
    private static KeyPair deviceKey;
    private static JwsTrust trust;

    @BeforeAll
    static void setUp() throws Exception {
        schema = JSON.readTree(Files.readAllBytes(SCHEMA));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        deviceKey = g.generateKeyPair();
        trust = JwsTrust.builder(TestClock.at("2026-10-14T10:00:00Z")).key(KID, deviceKey.getPublic()).build();
    }

    // --- a small JSON Schema checker: type, required, properties, items, enum, pattern, $ref --------------------

    private static List<String> validate(String def, JsonNode instance) {
        List<String> errors = new ArrayList<>();
        check(schema.path("$defs").path(def), instance, "$", errors);
        return errors;
    }

    private static void check(JsonNode s, JsonNode v, String at, List<String> errors) {
        if (s.has("$ref")) {
            String name = s.get("$ref").asText().substring("#/$defs/".length());
            check(schema.path("$defs").path(name), v, at, errors);
            return;
        }
        if (s.has("type") && !typeOk(s.get("type").asText(), v)) {
            errors.add(at + " should be " + s.get("type").asText());
            return;
        }
        if (s.has("enum")) {
            boolean found = false;
            for (JsonNode e : s.get("enum")) {
                found |= e.equals(v);
            }
            if (!found) {
                errors.add(at + " is not one of " + s.get("enum"));
            }
        }
        if (s.has("pattern") && v.isTextual() && !Pattern.compile(s.get("pattern").asText()).matcher(v.asText()).find()) {
            errors.add(at + " does not match " + s.get("pattern").asText());
        }
        if (v.isObject()) {
            for (JsonNode req : s.path("required")) {
                if (!v.has(req.asText())) {
                    errors.add(at + " lacks required " + req.asText());
                }
            }
            s.path("properties").fields().forEachRemaining(e -> {
                if (v.has(e.getKey())) {
                    check(e.getValue(), v.get(e.getKey()), at + "." + e.getKey(), errors);
                }
            });
        }
        if (v.isArray() && s.has("items")) {
            for (int i = 0; i < v.size(); i++) {
                check(s.get("items"), v.get(i), at + "[" + i + "]", errors);
            }
        }
    }

    private static boolean typeOk(String type, JsonNode v) {
        switch (type) {
            case "object":
                return v.isObject();
            case "array":
                return v.isArray();
            case "string":
                return v.isTextual();
            case "boolean":
                return v.isBoolean();
            case "number":
                return v.isNumber();
            default:
                return true;
        }
    }

    @Test
    void theCheckerRejectsWhatItShould() {
        assertFalse(validate("Hex32", JSON.getNodeFactory().textNode("nothex")).isEmpty());
        assertFalse(validate("CaptureBurstRequest", JSON.createObjectNode()).isEmpty(), "missing required fields");
        ObjectNode bad = JSON.createObjectNode();
        bad.put("transactionId", TXN);
        bad.put("count", 3); // a number: the SBI wants strings
        bad.put("intervalMs", "200");
        assertFalse(validate("CaptureBurstRequest", bad).isEmpty());
    }

    // --- the mock's outputs conform --------------------------------------------------------------------------

    private static JsonNode payload(String compactJws) throws Exception {
        return JSON.readTree(Jws.verify(compactJws, trust).payloadText());
    }

    @Test
    void deviceInfoCarriesTheLivenessProfileBlock() throws Exception {
        MockLivenessProfile device = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        JsonNode info = JSON.readTree(device.info());
        assertEquals(List.of(), validate("DeviceInfoLivenessProfile", info));
        assertEquals("0.1", info.path("livenessProfile").path("version").asText());
        assertTrue(info.path("livenessProfile").path("signedStream").asBoolean());
    }

    @Test
    void theWholeProfileRoundTripReachesCryptographicBinding() throws Exception {
        MockLivenessProfile device = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        KeyPair client = StreamKeyAgreement.newEphemeral();

        ObjectNode request = JSON.createObjectNode();
        request.put("version", "0.1");
        request.put("transactionId", TXN);
        request.put("clientEphemeralKey", Base64.getEncoder().encodeToString(client.getPublic().getEncoded()));
        assertEquals(List.of(), validate("KeyAgreementRequest", request));

        String answer = device.answerKeyAgreement(TXN, client.getPublic().getEncoded());
        assertEquals(List.of(), validate("KeyAgreementAnswer", payload(answer)));
        byte[] ks = StreamKeyAgreement.acceptDeviceAnswer(answer, trust, client, TXN);

        SignedStreamLock lock = new SignedStreamLock(ks, TXN.getBytes(StandardCharsets.UTF_8));
        Random r = new Random(9);
        for (int i = 0; i < 35; i++) {
            byte[] frame = new byte[48];
            r.nextBytes(frame);
            long t = 5_000L + 100L * i;
            assertTrue(lock.observe(frame, t, device.tag(frame, t)), "frame " + i);
            if (i == 29) {
                String cp = device.checkpoint();
                assertTrue(Pattern.matches("^[0-9]+:[0-9a-f]{64}$", new String(
                        Jws.verify(cp, trust).payloadBytes(), StandardCharsets.UTF_8)));
                assertTrue(lock.acceptCheckpoint(cp, trust));
            }
        }

        JsonNode extras = JSON.readTree(device.captureExtras(0.93));
        assertEquals(List.of(), validate("CaptureExtras", extras));
        lock.bindCapture(unhex(extras.get("streamTagHead").asText()));
        LockOutcome outcome = lock.check(null, null, null);
        assertTrue(outcome.passed(), outcome.toString());
        assertEquals(BindingLevel.CRYPTOGRAPHIC, SignedStreamLock.level(true, outcome));
    }

    @Test
    void anInjectedFrameBetweenDeviceAndClientIsCaught() throws Exception {
        MockLivenessProfile device = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        KeyPair client = StreamKeyAgreement.newEphemeral();
        byte[] ks = StreamKeyAgreement.acceptDeviceAnswer(
                device.answerKeyAgreement(TXN, client.getPublic().getEncoded()), trust, client, TXN);
        SignedStreamLock lock = new SignedStreamLock(ks, TXN.getBytes(StandardCharsets.UTF_8));
        Random r = new Random(10);
        boolean allGood = true;
        for (int i = 0; i < 20; i++) {
            byte[] frame = new byte[48];
            r.nextBytes(frame);
            byte[] tag = device.tag(frame, 5_000L + 100L * i);
            if (i == 8) {
                frame = new byte[48]; // a virtual camera / local proxy substitutes the image, tag untouched
            }
            allGood &= lock.observe(frame, 5_000L + 100L * i, tag);
        }
        assertFalse(allGood);
        assertEquals(9, lock.brokenAt());
        lock.bindCapture(device.streamTagHead());
        assertEquals("STREAM_TAG_MISMATCH", lock.check(null, null, null).code());
    }

    @Test
    void aCaptureFromAnotherSessionsStreamIsRefused() throws Exception {
        MockLivenessProfile mine = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        MockLivenessProfile other = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        KeyPair client = StreamKeyAgreement.newEphemeral();
        byte[] ks = StreamKeyAgreement.acceptDeviceAnswer(
                mine.answerKeyAgreement(TXN, client.getPublic().getEncoded()), trust, client, TXN);
        KeyPair client2 = StreamKeyAgreement.newEphemeral();
        other.answerKeyAgreement("ffffffffffffffffffffffffffffffff", client2.getPublic().getEncoded());
        SignedStreamLock lock = new SignedStreamLock(ks, TXN.getBytes(StandardCharsets.UTF_8));
        Random r = new Random(11);
        for (int i = 0; i < 10; i++) {
            byte[] frame = new byte[48];
            r.nextBytes(frame);
            lock.observe(frame, 5_000L + 100L * i, mine.tag(frame, 5_000L + 100L * i));
            other.tag(frame, 5_000L + 100L * i);
        }
        lock.bindCapture(other.streamTagHead()); // the attacker gets a capture signed against a different stream
        assertEquals("STREAM_HEAD_MISMATCH", lock.check(null, null, null).code());
    }

    @Test
    void theEmissionLogIsSignedAndConforms() throws Exception {
        MockLivenessProfile device = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        KeyPair client = StreamKeyAgreement.newEphemeral();
        device.answerKeyAgreement(TXN, client.getPublic().getEncoded());
        String log = device.emissionLog(Arrays.asList(
                new MockLivenessProfile.Emission("GREEN", 0, 400), new MockLivenessProfile.Emission("BLUE", 400, 450)));
        JsonNode body = payload(log);
        assertEquals(List.of(), validate("EmissionLog", body));
        assertEquals(2, body.get("emissions").size());
        assertEquals("450", body.get("emissions").get(1).get("durationMs").asText());
    }

    @Test
    void theProfileRefusesToTagBeforeAKeyIsAgreed() {
        MockLivenessProfile device = new MockLivenessProfile(deviceKey.getPrivate(), KID);
        assertThrows(IllegalStateException.class, () -> device.tag(new byte[4], 0));
        assertThrows(IllegalStateException.class, device::checkpoint);
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
