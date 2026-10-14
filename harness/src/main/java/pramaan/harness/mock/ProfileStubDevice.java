/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import pramaan.core.binding.IsoFaceRecord;
import pramaan.core.crypto.Jws;

/**
 * A runnable SBI device that speaks the whole Liveness Profile over real loopback HTTP (Bible Part 41): device info
 * with a signed {@code digitalId} and the profile block, a tagged and checkpointed M-JPEG stream after the key
 * agreement, signed captures that name the stream head, {@code ILLUMINATE} with a signed emission log, and
 * {@code CAPTUREBURST}. It signs with an {@code x5c} chain from a {@link DevelopmentCa}, so a client verifies it the way
 * it verifies a MOSIP device: through a CA certificate.
 *
 * <p>What this is, and is not: a reference for vendors and a target for the client, with the one JPEG you give it as
 * its "camera". A still photograph never answers a flash, so the flash cue will vote against it, which is the correct
 * verdict on a photograph. It is not a certified device and not MockMDS; it proves the transport and the cryptography
 * work over HTTP, not that any real sensor can do it.
 *
 * <pre>./gradlew :harness:runProfileDevice --args="--port 4501 --frame some-face.jpg"</pre>
 */
public final class ProfileStubDevice implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHECKPOINT_EVERY = 30;
    private static final long FRAME_INTERVAL_MS = 100;
    private static final long MAX_SCHEDULE_MS = 8_000;

    private final HttpServer server;
    private final byte[] frame;
    private final DevelopmentCa ca;
    private final Clock clock;
    private final Map<String, MockLivenessProfile> sessions = new ConcurrentHashMap<>();

    /** @param port 0 for any free port (tests) */
    public ProfileStubDevice(int port, byte[] jpeg, DevelopmentCa ca, Clock clock) throws IOException {
        this.frame = jpeg.clone();
        this.ca = ca;
        this.clock = clock;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "profile-stub-device");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private MockLivenessProfile newProfile() {
        return new MockLivenessProfile(payload -> sign(payload));
    }

    private String sign(byte[] payload) throws GeneralSecurityException {
        return Jws.signRs256(payload, ca.leafCertificate(), ca.leafKey());
    }

    // ------------------------------------------------------------------------------------------ transport

    private void handle(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            switch (ex.getRequestMethod()) {
                case "MOSIPDISC":
                    discovery(ex);
                    return;
                case "STREAM":
                    stream(ex, JSON.readTree(body));
                    return;
                case "RCAPTURE":
                    rcapture(ex, JSON.readTree(body));
                    return;
                case "ILLUMINATE":
                    illuminate(ex, JSON.readTree(body));
                    return;
                case "CAPTUREBURST":
                    captureBurst(ex, JSON.readTree(body));
                    return;
                default:
                    ex.sendResponseHeaders(405, -1);
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            try {
                ex.sendResponseHeaders(400, -1);
            } catch (IOException ignored) {
                // the client already went away
            }
        } finally {
            ex.close();
        }
    }

    private void discovery(HttpExchange ex) throws IOException, GeneralSecurityException {
        ObjectNode digital = JSON.createObjectNode();
        digital.put("serialNo", "PRAMAAN-PROFILE-0001");
        digital.put("make", "PRAMAAN");
        digital.put("model", "ProfileStub");
        digital.put("type", "Face");
        digital.put("deviceProvider", "PRAMAAN development");
        digital.put("dateTime", clock.instant().toString());
        ObjectNode device = JSON.createObjectNode();
        device.put("deviceId", "1");
        device.put("deviceStatus", "Ready");
        device.put("certification", "L0");
        device.put("purpose", "Registration");
        device.put("digitalId", sign(JSON.writeValueAsBytes(digital)));
        device.put("deviceCode", "pramaan-profile-stub");
        device.putArray("specVersion").add("0.9.5");
        device.putArray("deviceSubId").add("0");
        device.set("livenessProfile", JSON.readTree(newProfile().info()).path("livenessProfile"));
        ArrayNode list = JSON.createArrayNode();
        list.add(device);
        respond(ex, "application/json", JSON.writeValueAsBytes(list));
    }

    private void stream(HttpExchange ex, JsonNode body) throws IOException, GeneralSecurityException {
        JsonNode lp = body.path("livenessProfile");
        MockLivenessProfile profile = null;
        if (lp.isObject()) {
            String txn = lp.path("transactionId").asText();
            profile = newProfile();
            String answer = profile.answerKeyAgreement(txn, Base64.getDecoder().decode(lp.path("clientEphemeralKey").asText()));
            sessions.put(txn, profile);
            ex.getResponseHeaders().add("X-SBI-LP-Answer", answer);
        }
        long timeoutMs = Math.min(Long.parseLong(body.path("timeout").asText("60000")), 15 * 60_000L);
        ex.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=--BoundaryString");
        ex.sendResponseHeaders(200, 0);
        long startedAt = System.currentTimeMillis();
        try (OutputStream out = ex.getResponseBody()) {
            for (int i = 0; System.currentTimeMillis() - startedAt < timeoutMs; i++) {
                StringBuilder headers = new StringBuilder("--BoundaryString\r\nContent-Type:image/jpeg\r\nContent-Length:")
                        .append(frame.length).append("\r\n");
                if (profile != null) {
                    long t = System.currentTimeMillis() - startedAt;
                    headers.append("X-SBI-LP-Time:").append(t).append("\r\nX-SBI-LP-Tag:")
                            .append(MockLivenessProfile.hex(profile.tag(frame, t))).append("\r\n");
                    if ((i + 1) % CHECKPOINT_EVERY == 0) {
                        headers.append("X-SBI-LP-Checkpoint:").append(profile.checkpoint()).append("\r\n");
                    }
                }
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                part.write(headers.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                part.write(frame);
                part.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(part.toByteArray());
                out.flush();
                try {
                    Thread.sleep(FRAME_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException clientWentAway) {
            // The client closed the stream: that is how a session ends.
        }
    }

    // ------------------------------------------------------------------------------------------ captures

    private void rcapture(HttpExchange ex, JsonNode body) throws IOException, GeneralSecurityException {
        String txn = body.path("transactionId").asText();
        String previous = body.path("bio").path(0).path("previousHash").asText("");
        Map<String, String> none = Map.of();
        ObjectNode entry = signedFrame(txn, previous, none);
        ArrayNode list = JSON.createArrayNode();
        list.add(entry);
        ObjectNode root = JSON.createObjectNode();
        root.set("biometrics", list);
        respond(ex, "application/json", JSON.writeValueAsBytes(root));
    }

    private void captureBurst(HttpExchange ex, JsonNode body) throws IOException, GeneralSecurityException {
        String txn = body.path("transactionId").asText();
        int count = Integer.parseInt(body.path("count").asText());
        long interval = Long.parseLong(body.path("intervalMs").asText());
        ArrayNode list = JSON.createArrayNode();
        String previous = "";
        for (int i = 0; i < count; i++) {
            ObjectNode entry = signedFrame(txn, previous, Map.of("burstIndex", Integer.toString(i)));
            previous = entry.path("hash").asText();
            list.add(entry);
            sleep(Math.min(interval, 500));
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("biometrics", list);
        respond(ex, "application/json", JSON.writeValueAsBytes(root));
    }

    /** One RCAPTURE-shaped entry: the signed data, naming the stream head if the session has one, and its chain hash. */
    private ObjectNode signedFrame(String txn, String previousHash, Map<String, String> extra)
            throws GeneralSecurityException, IOException {
        byte[] iso = IsoFaceRecord.encode(IsoFaceRecord.ImageFormat.JPEG, frame, 0, 0);
        ObjectNode payload = JSON.createObjectNode();
        payload.put("bioType", "Face");
        payload.put("bioSubType", "");
        payload.put("purpose", "Registration");
        payload.put("env", "Staging");
        payload.put("transactionId", txn);
        payload.put("timestamp", clock.instant().toString());
        payload.put("bioValue", Base64.getUrlEncoder().withoutPadding().encodeToString(iso));
        payload.put("qualityScore", "80");
        payload.put("deviceCode", "pramaan-profile-stub");
        payload.put("deviceServiceVersion", "pramaan-profile-stub");
        MockLivenessProfile profile = sessions.get(txn);
        if (profile != null) {
            payload.put("streamTagHead", MockLivenessProfile.hex(profile.streamTagHead()));
        }
        extra.forEach(payload::put);
        ObjectNode entry = JSON.createObjectNode();
        entry.put("specVersion", "0.9.5");
        entry.put("data", sign(JSON.writeValueAsBytes(payload)));
        entry.put("hash", MockFrameSource.chainHash(previousHash, iso));
        return entry;
    }

    // ------------------------------------------------------------------------------------------ illuminate

    private void illuminate(HttpExchange ex, JsonNode body) throws IOException {
        MockLivenessProfile profile = sessions.get(body.path("transactionId").asText());
        if (profile == null) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        List<MockLivenessProfile.Emission> performed = new ArrayList<>();
        long at = 0;
        for (JsonNode step : body.path("schedule")) {
            long d = Long.parseLong(step.path("durationMs").asText());
            performed.add(new MockLivenessProfile.Emission(step.path("colour").asText(), at, d));
            at += d;
        }
        if (at > MAX_SCHEDULE_MS) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        sleep(at); // a real light takes this long; the answer comes after it has finished
        respond(ex, "application/jose", profile.emissionLog(performed).getBytes(StandardCharsets.UTF_8));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange ex, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------------------------------ run it

    public static void main(String[] args) throws Exception {
        int port = 4501;
        Path frame = Path.of("third_party", "mosip-mock-services", "MockMDS", "Biometric Devices", "Face", "Stream Image",
                "0.jpeg");
        Path work = Path.of("build", "profile-device");
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port":
                    port = Integer.parseInt(args[i + 1]);
                    break;
                case "--frame":
                    frame = Path.of(args[i + 1]);
                    break;
                case "--work":
                    work = Path.of(args[i + 1]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        DevelopmentCa ca = DevelopmentCa.create(work);
        ProfileStubDevice device = new ProfileStubDevice(port, Files.readAllBytes(frame), ca, Clock.systemUTC());
        System.out.println("PRAMAAN profile device on 127.0.0.1:" + device.port());
        System.out.println("  camera : " + frame + " (one still photograph, repeated)");
        System.out.println("  device CA for the client: " + ca.caCertificate().toAbsolutePath());
        System.out.println("  e.g. ./gradlew :desktop:runScaffold --args=\"--device-ca " + ca.caCertificate() + " --ports "
                + device.port() + "\"");
        Thread.currentThread().join();
    }
}
