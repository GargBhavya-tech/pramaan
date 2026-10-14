/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.binding.SignedStreamLock;
import pramaan.core.binding.StreamKeyAgreement;
import pramaan.core.binding.StreamTagChain;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.session.SessionContext;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.testing.TestClock;

/**
 * Build Map #100, Bible Part 41: {@code SbiFrameSource} against an in-process stub device that implements the SBI
 * Liveness Profile — the key agreement on the stream request, a tag on every frame — and against stubs that
 * misbehave. A plain device is covered by {@code SbiFrameSourceStubTest}.
 */
class SbiLivenessProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KID = "profile-device";
    private static final int FRAMES = 6;
    private static KeyPair deviceKey;
    private static KeyPair untrustedKey;
    private HttpServer server;

    private volatile boolean advertiseProfile = true;
    private volatile boolean sendAnswer = true;
    private volatile boolean signAnswerWithWrongKey;
    private volatile int corruptTagAtFrame = -1;

    @BeforeAll
    static void makeKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        deviceKey = gen.generateKeyPair();
        untrustedKey = gen.generateKeyPair();
    }

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] request = ex.getRequestBody().readAllBytes();
        switch (ex.getRequestMethod()) {
            case "MOSIPDISC":
                String digitalId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"serialNo\":\"SN1\",\"make\":\"Stub\",\"model\":\"Face1\",\"type\":\"Face\"}"
                                .getBytes(StandardCharsets.UTF_8));
                String profile = advertiseProfile
                        ? ",\"livenessProfile\":{\"version\":\"0.1\",\"signedStream\":true,\"captureBurst\":true,"
                                + "\"illuminatorControl\":[\"VISIBLE_WHITE\"],\"sensorTimestamps\":true}"
                        : "";
                respond(ex, "[{\"deviceId\":\"3\",\"deviceStatus\":\"Ready\",\"certification\":\"L0\",\"purpose\":\"Registration\","
                        + "\"digitalId\":\"" + digitalId + "\",\"deviceCode\":\"face-code\",\"specVersion\":[\"0.9.5\"],"
                        + "\"deviceSubId\":[\"0\"]" + profile + "}]");
                return;
            case "STREAM":
                stream(ex, JSON.readTree(request));
                return;
            default:
                ex.sendResponseHeaders(405, -1);
                ex.close();
        }
    }

    private void stream(HttpExchange ex, JsonNode body) throws IOException {
        StreamTagChain chain = null;
        JsonNode lp = body.path("livenessProfile");
        try {
            if (lp.isObject()) {
                String txn = lp.path("transactionId").asText();
                byte[] clientPublic = Base64.getDecoder().decode(lp.path("clientEphemeralKey").asText());
                KeyPair mine = StreamKeyAgreement.newEphemeral();
                byte[] devicePublic = mine.getPublic().getEncoded();
                byte[] ks = StreamKeyAgreement.sessionKey(mine.getPrivate(), clientPublic,
                        StreamKeyAgreement.transcriptHash(txn, devicePublic, clientPublic));
                chain = new StreamTagChain(ks, txn.getBytes(StandardCharsets.UTF_8));
                if (sendAnswer) {
                    String answer = "{\"version\":\"0.1\",\"transactionId\":\"" + txn + "\",\"deviceEphemeralKey\":\""
                            + Base64.getEncoder().encodeToString(devicePublic) + "\",\"clientEphemeralKeyHash\":\""
                            + StreamKeyAgreement.keyHash(clientPublic) + "\"}";
                    KeyPair signer = signAnswerWithWrongKey ? untrustedKey : deviceKey;
                    ex.getResponseHeaders().add("X-SBI-LP-Answer",
                            Jws.signRs256(answer.getBytes(StandardCharsets.UTF_8), KID, signer.getPrivate()));
                }
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
        ex.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=--BoundaryString");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            for (int i = 0; i < FRAMES; i++) {
                byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) i, (byte) 0xFF, (byte) 0xD9};
                StringBuilder headers = new StringBuilder("--BoundaryString\r\nContent-Type:image/jpeg\r\nContent-Length:")
                        .append(jpeg.length).append("\r\n");
                if (chain != null) {
                    long t = 1_000L + 100L * i;
                    byte[] tag = chain.next(jpeg, t);
                    if (i == corruptTagAtFrame) {
                        tag[0] ^= 0x01;
                    }
                    headers.append("X-SBI-LP-Time:").append(t).append("\r\nX-SBI-LP-Tag:").append(hex(tag)).append("\r\n");
                }
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                part.write(headers.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                part.write(jpeg);
                part.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(part.toByteArray());
                out.flush();
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void respond(HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SbiFrameSource source() {
        TestClock clock = TestClock.at("2026-10-14T10:00:00Z");
        JwsTrust trust = JwsTrust.builder(clock).key(KID, deviceKey.getPublic()).build();
        return new SbiFrameSource(SbiConfig.builder(trust, clock).ports(server.getAddress().getPort(),
                server.getAddress().getPort()).probeTimeout(Duration.ofSeconds(10)).build());
    }

    private static SessionContext session() throws Exception {
        return SessionContext.start(Workflow.RESIDENT,
                YamlPolicySource.of(java.nio.file.Path.of("..", "config", "pramaan-policy.yaml")).load(),
                TestClock.at("2026-10-14T10:00:00Z"), new SecureRandom());
    }

    private static final FrameSink IGNORE = new FrameSink() {
        @Override
        public void onFrame(Frame frame) {
        }

        @Override
        public void onDeviceEvent(DeviceEvent event) {
        }
    };

    /** Runs the stream to its end and returns the lock the source built, once every frame has been read. */
    private SignedStreamLock streamToEnd(SbiFrameSource source) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        source.start(session(), new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
                if (event.type() == DeviceEvent.Type.DISCONNECTED) {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "the stream never ended");
        return source.streamLock().orElseThrow();
    }

    @Test
    void aDeviceThatAdvertisesTheProfileDeclaresItsCapabilities() throws Exception {
        try (SbiFrameSource source = source()) {
            var caps = source.discover();
            assertTrue(caps.has(Capability.SIGNED_STREAM));
            assertTrue(caps.has(Capability.CAPTURE_BURST));
            assertTrue(caps.has(Capability.SENSOR_TIMESTAMPS));
            assertTrue(caps.has(Capability.RESIDENT_DISPLAY), "a visible illuminator faces the resident");
        }
    }

    @Test
    void anUntouchedStreamIsAuthenticatedFrameByFrame() throws Exception {
        try (SbiFrameSource source = source()) {
            source.discover();
            SignedStreamLock lock = streamToEnd(source);
            assertEquals(FRAMES, lock.framesObserved());
            assertEquals(-1, lock.brokenAt());
        }
    }

    @Test
    void aTagTamperedInTransitBreaksTheChainAtThatFrame() throws Exception {
        corruptTagAtFrame = 3;
        try (SbiFrameSource source = source()) {
            source.discover();
            SignedStreamLock lock = streamToEnd(source);
            assertEquals(4, lock.brokenAt(), "frame index 3 is the fourth frame; tag_0 is index 0");
        }
    }

    @Test
    void aDeviceThatAdvertisesTheProfileButSendsNoSignedAnswerIsRefused() throws Exception {
        sendAnswer = false;
        try (SbiFrameSource source = source()) {
            source.discover();
            DeviceException e = assertThrows(DeviceException.class, () -> source.start(session(), IGNORE));
            assertEquals(DeviceException.Reason.UNTRUSTED, e.reason());
            assertFalse(source.streamLock().isPresent());
        }
    }

    @Test
    void anAnswerSignedByAnUntrustedKeyIsRefused() throws Exception {
        signAnswerWithWrongKey = true;
        try (SbiFrameSource source = source()) {
            source.discover();
            DeviceException e = assertThrows(DeviceException.class, () -> source.start(session(), IGNORE));
            assertEquals(DeviceException.Reason.UNTRUSTED, e.reason());
        }
    }

    @Test
    void aPlainDeviceIsUnchanged() throws Exception {
        advertiseProfile = false;
        try (SbiFrameSource source = source()) {
            var caps = source.discover();
            assertFalse(caps.has(Capability.SIGNED_STREAM));
            CountDownLatch done = new CountDownLatch(1);
            source.start(session(), new FrameSink() {
                @Override
                public void onFrame(Frame frame) {
                    assertEquals(null, frame.streamTag(), "no profile, no tag");
                }

                @Override
                public void onDeviceEvent(DeviceEvent event) {
                    if (event.type() == DeviceEvent.Type.DISCONNECTED) {
                        done.countDown();
                    }
                }
            });
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertFalse(source.streamLock().isPresent());
        }
    }
}
