/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.session.SessionContext;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;

/**
 * Build Map #12, #13 against an in-process stub device service speaking the same wire format as
 * MockMDS (custom verbs, string scalars, the boundary quirk). Runs everywhere, no MockMDS needed.
 */
class SbiFrameSourceStubTest {

    private static final String KID = "stub-device";
    // Both keys are made once, never on the request path: RSA keygen is slow and unbounded (the JDK default is
    // 3072 bits, up to seconds on a loaded machine), and inside the handler it raced the client's RCAPTURE
    // timeout, so an untrusted capture intermittently came back as TIMEOUT instead of UNTRUSTED.
    private static KeyPair deviceKey;
    private static KeyPair untrustedKey;
    private HttpServer server;
    private final AtomicReference<String> lastRcaptureBody = new AtomicReference<>();
    private final AtomicInteger rcaptureCount = new AtomicInteger();
    private volatile boolean signWithWrongKey;
    private volatile boolean malformedData;

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

    private int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] request = ex.getRequestBody().readAllBytes();
        switch (ex.getRequestMethod()) {
            case "MOSIPDISC":
                String digitalId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"serialNo\":\"SN1\",\"make\":\"Stub\",\"model\":\"Face1\",\"type\":\"Face\"}"
                                .getBytes(StandardCharsets.UTF_8));
                respond(ex, "[{\"deviceId\":\"3\",\"deviceStatus\":\"Ready\",\"certification\":\"L0\",\"purpose\":\"Registration\","
                        + "\"digitalId\":\"" + digitalId + "\",\"deviceCode\":\"face-code\",\"specVersion\":[\"0.9.5\"],"
                        + "\"deviceSubId\":[\"0\"]}]");
                return;
            case "RCAPTURE":
                String body = new String(request, StandardCharsets.UTF_8);
                lastRcaptureBody.set(body);
                rcaptureCount.incrementAndGet();
                if (malformedData) {
                    respond(ex, "{\"biometrics\":[{\"specVersion\":\"0.9.5\",\"data\":\"not-a-jws\",\"hash\":\"00\"}]}");
                    return;
                }
                String txn = body.replaceAll(".*\"transactionId\":\"([^\"]+)\".*", "$1");
                byte[] iso = "FAC\u0000synthetic".getBytes(StandardCharsets.UTF_8);
                String bioValue = Base64.getUrlEncoder().withoutPadding().encodeToString(iso);
                String payload = "{\"bioType\":\"Face\",\"transactionId\":\"" + txn + "\",\"timestamp\":\"2026-09-23T10:00:01Z\","
                        + "\"bioValue\":\"" + bioValue + "\",\"qualityScore\":\"80\",\"purpose\":\"Registration\"}";
                try {
                    KeyPair signer = signWithWrongKey ? untrustedKey : deviceKey;
                    String jws = Jws.signRs256(payload.getBytes(StandardCharsets.UTF_8), KID, signer.getPrivate());
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    byte[] prev = sha.digest(new byte[0]);
                    byte[] cur = MessageDigest.getInstance("SHA-256").digest(iso);
                    byte[] both = new byte[64];
                    System.arraycopy(prev, 0, both, 0, 32);
                    System.arraycopy(cur, 0, both, 32, 32);
                    StringBuilder hash = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256").digest(both)) {
                        hash.append(String.format("%02X", b));
                    }
                    respond(ex, "{\"biometrics\":[{\"specVersion\":\"0.9.5\",\"data\":\"" + jws + "\",\"hash\":\"" + hash + "\"}]}");
                } catch (java.security.GeneralSecurityException e) {
                    throw new IOException(e);
                }
                return;
            case "STREAM":
                ex.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=--BoundaryString");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    for (int i = 0; i < 5; i++) {
                        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) i, (byte) 0xFF, (byte) 0xD9};
                        ByteArrayOutputStream part = new ByteArrayOutputStream();
                        part.write(("--BoundaryString\r\nContent-Type:image/jpeg\r\nContent-Length:" + jpeg.length + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        part.write(jpeg);
                        part.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.write(part.toByteArray());
                        out.flush();
                    }
                }
                return;
            default:
                ex.sendResponseHeaders(405, -1);
                ex.close();
        }
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
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        JwsTrust trust = JwsTrust.builder(clock).key(KID, deviceKey.getPublic()).build();
        // These tests check the protocol, not discovery speed. The production 800 ms probe timeout lost races
        // against the first request of a cold JVM on a loaded machine, so the healthy stub gets 10 s.
        return new SbiFrameSource(SbiConfig.builder(trust, clock).ports(port(), port())
                .probeTimeout(Duration.ofSeconds(10)).build());
    }

    @Test
    void discoversTheFaceDeviceAndItsLevel() throws Exception {
        try (SbiFrameSource source = source()) {
            assertEquals("3", source.discover().device().deviceId());
            assertEquals(DeviceLevel.L0, source.discover().device().level());
        }
    }

    @Test
    void captureIsSignatureVerifiedAndEchoesTheNonceSentAsStrings() throws Exception {
        try (SbiFrameSource source = source()) {
            source.discover();
            String nonce = pramaan.core.session.SessionNonce.generate(new SecureRandom()).value();
            SignedCapture capture = source.capture(new CaptureRequest(nonce, "", 40, Duration.ofSeconds(5)));
            assertEquals(nonce, capture.verifiedTransactionId());
            assertEquals("kid:" + KID, capture.signer());
            assertTrue(lastRcaptureBody.get().contains("\"timeout\":\"5000\""), lastRcaptureBody.get());
        }
    }

    @Test
    void aCaptureSignedByAnUntrustedKeyIsRefused() throws Exception {
        signWithWrongKey = true;
        try (SbiFrameSource source = source()) {
            source.discover();
            DeviceException e = assertThrows(DeviceException.class,
                    () -> source.capture(new CaptureRequest("abcd-1234", "", 40, Duration.ofSeconds(5))));
            assertEquals(DeviceException.Reason.UNTRUSTED, e.reason());
            assertEquals(1, rcaptureCount.get(), "an untrusted capture must be refused, not silently retried");
        }
    }

    @Test
    void signedDataThatIsNotAJwsIsADeviceProtocolErrorNotAnEngineFault() throws Exception {
        malformedData = true;
        try (SbiFrameSource source = source()) {
            source.discover();
            DeviceException e = assertThrows(DeviceException.class,
                    () -> source.capture(new CaptureRequest("abcd-1234", "", 40, Duration.ofSeconds(5))));
            assertEquals(DeviceException.Reason.PROTOCOL, e.reason());
        }
    }

    @Test
    void streamDeliversFramesThenADisconnectEvent() throws Exception {
        List<Frame> frames = new CopyOnWriteArrayList<>();
        List<DeviceEvent> events = new CopyOnWriteArrayList<>();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        try (SbiFrameSource source = source()) {
            source.discover();
            SessionContext session = SessionContext.start(Workflow.RESIDENT,
                    YamlPolicySource.of(java.nio.file.Path.of("..", "config", "pramaan-policy.yaml")).load(),
                    TestClock.at("2026-09-23T10:00:00Z"), new SecureRandom());
            source.start(session, new FrameSink() {
                @Override
                public void onFrame(Frame frame) {
                    frames.add(frame);
                    order.add("frame");
                }

                @Override
                public void onDeviceEvent(DeviceEvent event) {
                    events.add(event);
                    order.add(event.type().name());
                    if (event.type() == DeviceEvent.Type.DISCONNECTED) {
                        disconnected.countDown();
                    }
                }
            });
            assertTrue(disconnected.await(15, TimeUnit.SECONDS), "no DISCONNECTED event; events=" + events);
        }
        // Every frame the stub sent is either delivered or reported dropped, and all of it comes before the
        // disconnect: a consumer never sees DISCONNECTED followed by more frames (Bible Part 22).
        long dropped = events.stream().filter(e -> e.type() == DeviceEvent.Type.FRAMES_DROPPED)
                .mapToLong(e -> Long.parseLong(e.detail().split(" ")[0])).sum();
        assertEquals(5, frames.size() + dropped, "order=" + order);
        assertEquals("DISCONNECTED", order.get(order.size() - 1), "order=" + order);
        assertEquals(DeviceEvent.Type.CONNECTED, events.get(0).type());
    }

    @Test
    void noDeviceServiceMeansUnavailable() {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        JwsTrust trust = JwsTrust.builder(clock).key(KID, deviceKey.getPublic()).build();
        int unused = port() == 65535 ? 65534 : port() + 1;
        try (SbiFrameSource source = new SbiFrameSource(SbiConfig.builder(trust, clock).ports(unused, unused)
                .probeTimeout(Duration.ofMillis(300)).build())) {
            assertEquals(DeviceException.Reason.UNAVAILABLE, assertThrows(DeviceException.class, source::discover).reason());
        }
    }
}
