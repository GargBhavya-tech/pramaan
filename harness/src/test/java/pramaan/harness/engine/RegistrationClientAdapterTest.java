/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.CaptureTicket;
import io.mosip.registration.liveness.LivenessRefusedException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.Workflow;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.evidence.EvidencePackager;
import pramaan.core.evidence.EvidenceRecord;
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.SessionState;
import pramaan.core.source.CaptureRequest;
import pramaan.core.testing.TestClock;
import pramaan.desktop.ImageIoJpegEncoder;
import pramaan.harness.mock.DevelopmentCa;
import pramaan.harness.mock.ProfileStubDevice;
import pramaan.regclient.AdapterSettings;
import pramaan.regclient.PramaanLivenessHook;
import pramaan.sbi.SbiRequests;

/**
 * The Registration Client's hook, driven the way the client would drive it, against the runnable reference device over real
 * loopback HTTP: the test is the client (it decodes frames, makes the rCapture call itself with the ticket the hook returns,
 * and keeps the raw response), and PRAMAAN is the hook. The engine's analysers are the scripted resident, so this proves the
 * plumbing and the binding, not the detectors.
 */
class RegistrationClientAdapterTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    private static EngineBundle bundle;
    private static DevelopmentCa ca;
    private static JwsTrust trust;
    private static KeyPair signerKey;
    private static EvidencePackager packager;

    private ProfileStubDevice device;
    private TestClock clock;
    private SimulatedResident resident;
    private PramaanLivenessHook hook;

    @BeforeAll
    static void setUp(@TempDir Path dir) throws Exception {
        assumeTrue(DevelopmentCa.available(), "no keytool in this JDK");
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));

        ca = DevelopmentCa.create(dir);
        X509Certificate anchor = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(ca.caCertificate())));
        trust = JwsTrust.builder(Clock.systemUTC()).anchor(anchor).build();

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        signerKey = g.generateKeyPair();
        KeyPair serverKey = g.generateKeyPair();
        packager = new EvidencePackager(new EvidenceSigner(signerKey.getPrivate(), "evidence"), serverKey.getPublic(),
                new ImageIoJpegEncoder(), new SecureRandom());
    }

    @AfterEach
    void stopDevice() {
        if (device != null) {
            device.close();
        }
    }

    @BeforeEach
    void configure() throws Exception {
        clock = TestClock.at("2026-09-23T10:00:00Z");
        // the device stamps its captures with the session's clock: with the real one, the nonce lock would (rightly) call a
        // capture signed in 2026-10 stale against a session running on a simulated 2026-09 clock
        device = new ProfileStubDevice(0, JPEG, ca, clock);
        resident = new SimulatedResident(clock);
        settings(Duration.ofSeconds(30));
        hook = new PramaanLivenessHook();
    }

    private void settings(Duration clearanceTimeout) {
        PramaanLivenessHook.configure(AdapterSettings.builder(bundle, trust, packager, clock, new SecureRandom(),
                () -> new AdapterSettings.Subject(Workflow.RESIDENT, "R1", "OP1", "c1"))
                .sbi("127.0.0.1", device.port(), device.port())
                .clearanceTimeout(clearanceTimeout)
                .engineFactory((session, source, listener, score) -> (caps, dev) -> LivenessEngine.builder(bundle, session)
                        .device(source, trust, dev.device().level())
                        .analysers(resident, c -> SimulatedResident.RESIDENT)
                        .capabilities(caps)
                        .random(new SecureRandom())
                        .listener(new EngineListener() {
                            @Override
                            public void stateChanged(SessionState state) {
                                listener.stateChanged(state);
                            }

                            @Override
                            public void prompt(ChallengePrompt prompt, int step, int steps) {
                                listener.prompt(prompt, step, steps);
                                resident.respond(prompt); // the resident reads the screen and acts
                            }
                        })
                        .requestedScore(score)
                        .build())
                .build());
    }

    /** What the Registration Client does after the hook clears the capture: the rCapture call, and keeping the raw response. */
    private BiometricsDto clientCapture(CaptureTicket ticket, String transactionIdToSend) throws Exception {
        CaptureRequest request = new CaptureRequest(transactionIdToSend, ticket.previousHash(), 40, Duration.ofSeconds(10));
        byte[] body = SbiRequests.rcaptureFace(request, "1", "1", "Developer", "0.9.5", Clock.systemUTC());
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + device.port() + "/capture"))
                .method("RCAPTURE", HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        String element = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("biometrics").get(0)
                .toString();
        BiometricsDto dto = new BiometricsDto();
        dto.setSbiResponse(element);
        return dto;
    }

    private static final class ClientRun {
        final AtomicReference<CaptureTicket> ticket = new AtomicReference<>();
        final AtomicReference<String> evidence = new AtomicReference<>();
        final AtomicReference<Throwable> problem = new AtomicReference<>();
        final AtomicReference<String> sentTransactionId = new AtomicReference<>();
    }

    /** Streams frames like the client's Streamer, while a second thread does what the client's capture task does. */
    private ClientRun run(boolean sendTheTicketsTransactionId) throws Exception {
        ClientRun out = new ClientRun();
        hook.beginFaceScan("FACE");
        Thread capture = new Thread(() -> {
            try {
                CaptureTicket t = hook.awaitCaptureClearance();
                out.ticket.set(t);
                String id = sendTheTicketsTransactionId ? t.transactionId() : "SOMEONE-ELSES-SESSION-0001";
                out.sentTransactionId.set(id);
                out.evidence.set(hook.afterCapture(List.of(clientCapture(t, id))));
            } catch (Throwable e) {
                out.problem.set(e);
            }
        }, "test-client-capture");
        capture.start();
        for (int i = 0; i < 900 && capture.isAlive(); i++) {
            clock.advanceMillis(100);
            hook.onPreviewFrame(JPEG, clock.instant().toEpochMilli());
            Thread.sleep(3);
        }
        capture.join(20_000);
        return out;
    }

    @Test
    void anAcceptedScanReturnsSignedEvidenceAndTheDeviceSignedTheEnginesOwnNonce() throws Exception {
        ClientRun r = run(true);
        assertEquals(null, r.problem.get(), String.valueOf(r.problem.get()));
        CaptureTicket ticket = r.ticket.get();
        assertNotNull(ticket, "the hook never cleared the capture");
        assertTrue(ticket.transactionId().matches("[A-Za-z0-9-]{4,50}"), "an SBI-valid transaction id: " + ticket.transactionId());
        assertNotNull(r.evidence.get());
        assertTrue(r.evidence.get().startsWith("ey"), "a compact JWS");
        EvidenceRecord verified = EvidenceSigner.verify(r.evidence.get(),
                JwsTrust.builder(Clock.systemUTC()).key("evidence", signerKey.getPublic()).build());
        assertEquals("ACCEPTED", verified.json().path("outcome").asText());
        assertEquals("OP1", verified.json().path("operatorId").asText());
        assertEquals(ticket.transactionId(), r.sentTransactionId.get());
        assertTrue(verified.json().has("binding"), verified.json().toString());
    }

    @Test
    void aCaptureMadeUnderAnotherTransactionIdIsRefusedAndNothingIsStored() throws Exception {
        ClientRun r = run(false);
        assertNotNull(r.ticket.get(), "the hook never cleared the capture");
        assertTrue(r.problem.get() instanceof LivenessRefusedException, String.valueOf(r.problem.get()));
        assertEquals(null, r.evidence.get(), "no evidence may exist for a capture the nonce lock rejected");
    }

    @Test
    void ifTheEngineNeverReachesItsCaptureStepTheScanIsRefusedNotWaitedOnForever() throws Exception {
        settings(Duration.ofSeconds(1));
        hook.beginFaceScan("FACE");
        assertThrows(LivenessRefusedException.class, () -> hook.awaitCaptureClearance());
    }

    @Test
    void aClientThatKeptNoRawResponseCannotBeBound() throws Exception {
        ClientRun r = new ClientRun();
        hook.beginFaceScan("FACE");
        Thread capture = new Thread(() -> {
            try {
                r.ticket.set(hook.awaitCaptureClearance());
                hook.afterCapture(List.of(new BiometricsDto()));
            } catch (Throwable e) {
                r.problem.set(e);
            }
        }, "test-client-capture");
        capture.start();
        for (int i = 0; i < 900 && capture.isAlive(); i++) {
            clock.advanceMillis(100);
            hook.onPreviewFrame(JPEG, clock.instant().toEpochMilli());
            Thread.sleep(3);
        }
        capture.join(20_000);
        assertTrue(r.problem.get() instanceof LivenessRefusedException, String.valueOf(r.problem.get()));
    }

    @Test
    void withoutSettingsEveryScanIsRefused() throws Exception {
        PramaanLivenessHook.reset();
        try {
            PramaanLivenessHook bare = new PramaanLivenessHook();
            bare.beginFaceScan("FACE");
            assertThrows(LivenessRefusedException.class, bare::awaitCaptureClearance);
            assertThrows(LivenessRefusedException.class, () -> bare.afterCapture(List.of(new BiometricsDto())));
        } finally {
            settings(Duration.ofSeconds(30));
        }
    }
}
