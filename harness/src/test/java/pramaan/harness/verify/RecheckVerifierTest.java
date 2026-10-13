/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.Workflow;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.CaptureBinder;
import pramaan.core.binding.Embeddings;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.CbeffOthers;
import pramaan.core.evidence.EvidenceBuilder;
import pramaan.core.evidence.EvidenceRecord;
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.evidence.SampleCrypto;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.FusionResult;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.fusion.Veto;
import pramaan.core.fusion.ZoneDecider;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionEventRouter;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;
import pramaan.desktop.ImageIoJpegEncoder;
import pramaan.harness.mock.MockFrameSource;

/**
 * Build Map #62: a real accepted session produces signed evidence; the server re-check routes the clean,
 * tampered-chain, unapproved-model and client/server-disagreement cases as specified.
 */
class RecheckVerifierTest {

    static KeyPair clientKey;
    static KeyPair serverKey;
    private static final float[] FACE = face();

    private EngineBundle bundle;
    private MockFrameSource source;
    private String evidenceJws;
    private String captureJws;
    private EvidenceRecord record;

    private static float[] face() {
        float[] v = new float[128];
        Arrays.fill(v, 0.01f);
        v[0] = 1;
        return Embeddings.normalize(v);
    }

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        clientKey = g.generateKeyPair();
        serverKey = g.generateKeyPair();
    }

    /** One genuine accepted attempt through the mock device, with its evidence signed and samples encrypted. */
    @BeforeEach
    void acceptedSession() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        source = MockFrameSource.builder(clock).delayer(clock::advance).fps(10)
                .neutral(List.of(new byte[] {(byte) 0xFF, (byte) 0xD8, 1})).build();
        LivenessSession s = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", new SessionRegistry(), e -> { },
                clock, new SecureRandom());
        SessionEventRouter router = new SessionEventRouter(s);
        source.start(null, new FrameSink() {
            @Override
            public void onFrame(Frame f) {
                router.onFrame(f, f.copyBytes(), 1);
                if (s.state() == SessionState.PASSIVE) {
                    s.recordEmbedding(FACE);
                    s.recordCue(CueResult.vote("passive", 0.97, 0));
                }
            }

            @Override
            public void onDeviceEvent(DeviceEvent e) {
                router.onDeviceEvent(e);
            }
        });
        s.start();
        s.faceAcquired();
        for (int i = 0; i < 20; i++) {
            source.step();
        }
        FusionResult fused = new RiskFusion(bundle.table()).fuse(
                List.of(CueResult.vote("passive", 0.97, 0), CueResult.vote("moire", 0.95, 0)), Workflow.RESIDENT, DeviceLevel.L1);
        Decision decision = new ZoneDecider(() -> 0.999, bundle.table().disagreementLogitGap()).decide(fused,
                bundle.table().operatingPoint("resident-default", "default"), bundle.policy().profile(Workflow.RESIDENT),
                EnumSet.noneOf(Veto.class));
        s.passiveDecided(decision, null);
        SignedCapture capture = source.capture(s.captureRequest("", 40, Duration.ofSeconds(10)));
        s.captured();
        BindingResult result = new CaptureBinder(source.trust(), bundle.table().thetaSame(),
                bundle.policy().sessionTimeouts().bindingWindow(), bundle.policy().sessionTimeouts().captureMaxSkew())
                .bind(s.bindingInput(capture, FACE, 1, List.of()));
        s.bindingVerified(result);
        assertEquals(SessionState.ACCEPTED, s.state());

        List<byte[]> crops = new ArrayList<>();
        ImageIoJpegEncoder jpeg = new ImageIoJpegEncoder();
        for (int i = 0; i < 3; i++) {
            // Smooth synthetic crops: random noise is incompressible and says nothing about real JPEG sizes
            // (realFaceCropsFitTheSampleBudget measures real ones).
            byte[] px = new byte[112 * 112 * 3];
            for (int p = 0; p < 112 * 112; p++) {
                int x = p % 112;
                int y = p / 112;
                px[3 * p] = (byte) (80 + x + i * 10);
                px[3 * p + 1] = (byte) (60 + y);
                px[3 * p + 2] = (byte) (120 + ((x * y) >> 7));
            }
            crops.add(jpeg.encode(RgbImage.of(112, 112, px)));
        }
        record = EvidenceBuilder.forSession(s, bundle).device(capture.device()).fusion(decision).binding(result)
                .operator("OP1", "c1").accessibility(Set.of())
                .samples(SampleCrypto.encrypt(crops, serverKey.getPublic(), new SecureRandom())).build(clock.instant());
        evidenceJws = new EvidenceSigner(clientKey.getPrivate(), "evidence").sign(record);
        captureJws = capture.signedData();
    }

    private RecheckVerifier verifier(Set<String> models, double serverScore) {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        return new RecheckVerifier(JwsTrust.builder(clock).key("evidence", clientKey.getPublic()).build(), source.trust(), models,
                Set.of(bundle.policySha256()), serverKey.getPrivate(), crop -> serverScore, 0.2);
    }

    private Set<String> approved() {
        return Set.copyOf(bundle.table().models().values());
    }

    @Test
    void aCleanAcceptedSessionIsConfirmedWithinTheSizeBudget() {
        assertTrue(record.sizeWithoutSamples() <= EvidenceRecord.JSON_BUDGET_BYTES, "JSON " + record.sizeWithoutSamples());
        assertTrue(record.samplesSize() <= EvidenceRecord.SAMPLES_BUDGET_BYTES, "samples " + record.samplesSize());
        System.out.printf("evidence: %d B JSON + %d B encrypted samples%n", record.sizeWithoutSamples(), record.samplesSize());
        RecheckReport r = verifier(approved(), 0.9).verify(evidenceJws, captureJws);
        assertEquals(RecheckReport.Route.CONFIRMED, r.route(), r.toJson().toString());
    }

    @Test
    void realFaceCropsFitTheSampleBudget() throws Exception {
        Path stream = Path.of("..", "sbi", "testdata", "stream-sample.mjpeg");
        Path modelsDir = Path.of("..", "models");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(stream) && pramaan.harness.Models.present(modelsDir),
                "MockMDS stream fixture or models absent");
        List<byte[]> crops = new ArrayList<>();
        ImageIoJpegEncoder jpeg = new ImageIoJpegEncoder();
        pramaan.desktop.ImageIoFrameDecoder decoder = new pramaan.desktop.ImageIoFrameDecoder();
        try (java.io.InputStream in = Files.newInputStream(stream);
                pramaan.core.detect.YuNetDetector yunet = pramaan.harness.Models.detector(modelsDir)) {
            pramaan.sbi.MjpegStreamReader reader = new pramaan.sbi.MjpegStreamReader(in,
                    "multipart/x-mixed-replace; boundary=--BoundaryString", TestClock.at("2026-09-23T10:00:00Z"), null);
            Frame f;
            int seen = 0;
            while ((f = reader.next()) != null && crops.size() < pramaan.core.evidence.SampleSelector.MAX) {
                if (seen++ % 10 != 0) {
                    continue;
                }
                RgbImage frame = decoder.decode(f);
                List<pramaan.core.detect.FaceBox> faces = yunet.detect(frame);
                if (!faces.isEmpty()) {
                    crops.add(jpeg.encode(pramaan.core.evidence.SampleSelector.contextCrop(frame, faces.get(0))));
                }
            }
        }
        assertEquals(pramaan.core.evidence.SampleSelector.MAX, crops.size());
        int size = pramaan.core.evidence.CanonicalJson.write(SampleCrypto.encrypt(crops, serverKey.getPublic(), new SecureRandom())).length;
        System.out.printf("5 real 112x112 face crops: %d B encrypted (JPEG sizes %s)%n", size,
                crops.stream().map(c -> Integer.toString(c.length)).collect(java.util.stream.Collectors.joining(", ")));
        assertTrue(size <= EvidenceRecord.SAMPLES_BUDGET_BYTES, "real crops " + size + " B over the budget");
    }

    @Test
    void aTamperedChainHeadIsRejected() throws Exception {
        // Even re-signed with the client's own key: the server recomputes H_final from the capture.
        ObjectNode json = record.json();
        json.put("hashChainHead", "0".repeat(64));
        String forged = new EvidenceSigner(clientKey.getPrivate(), "evidence").sign(EvidenceRecord.of(json));
        RecheckReport r = verifier(approved(), 0.9).verify(forged, captureJws);
        assertEquals(RecheckReport.Route.REJECT, r.route());
        assertTrue(r.has("CHAIN_BINDING"), r.toJson().toString());
    }

    @Test
    void anotherSessionsCaptureIsRejected() throws Exception {
        SignedCapture other = source.capture(new CaptureRequest("ffffffffffffffffffffffffffffffff", "", 40, Duration.ofSeconds(10)));
        RecheckReport r = verifier(approved(), 0.9).verify(evidenceJws, other.signedData());
        assertEquals(RecheckReport.Route.REJECT, r.route());
        assertTrue(r.has("NONCE_ECHO") && r.has("CHAIN_BINDING"), r.toJson().toString());
    }

    @Test
    void anUnapprovedModelIsAFindingForAdjudicationNotACrash() {
        RecheckReport r = verifier(Set.of(), 0.9).verify(evidenceJws, captureJws);
        assertEquals(RecheckReport.Route.ADJUDICATE, r.route());
        assertTrue(r.has("UNAPPROVED_MODEL"));
    }

    @Test
    void aClientAcceptTheServerDoubtsGoesToAdjudication() {
        RecheckReport r = verifier(approved(), 0.05).verify(evidenceJws, captureJws);
        assertEquals(RecheckReport.Route.ADJUDICATE, r.route());
        assertTrue(r.has("CLIENT_SERVER_DISAGREEMENT"), r.toJson().toString());
    }

    @Test
    void aForgedEvidenceSignatureIsRejected() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        String bySomeoneElse = new EvidenceSigner(g.generateKeyPair().getPrivate(), "evidence").sign(record);
        assertEquals(RecheckReport.Route.REJECT, verifier(approved(), 0.9).verify(bySomeoneElse, captureJws).route());
    }

    @Test
    void theEvidenceTravelsInTheBirAndTheCliRoutesItInOneCommand(@TempDir Path dir) throws Exception {
        String bir = "<BIR xmlns=\"http://standards.iso.org/iso-iec/19785/-3/ed-2/\"><BIRInfo><Integrity>false</Integrity></BIRInfo>"
                + "<BIR><BIRInfo><Integrity>false</Integrity></BIRInfo><BDBInfo><Type>Face</Type></BDBInfo><BDB>AAAA</BDB></BIR></BIR>";
        byte[] packet = CbeffOthers.embed(bir.getBytes(StandardCharsets.UTF_8), "Face", CbeffOthers.KEY, evidenceJws);
        Files.writeString(dir.resolve("e.jws"), CbeffOthers.read(packet, "Face", CbeffOthers.KEY).orElseThrow());
        Files.writeString(dir.resolve("c.jws"), captureJws);
        Files.write(dir.resolve("ev.der"), clientKey.getPublic().getEncoded());
        Files.write(dir.resolve("dev.der"), source.trustedPublicKey().getEncoded());
        Files.write(dir.resolve("server.der"), serverKey.getPrivate().getEncoded());
        Files.writeString(dir.resolve("allow.json"), "{\"models\":[\"" + String.join("\",\"", approved()) + "\"],\"policies\":[\""
                + bundle.policySha256() + "\"],\"serverRejectBelow\":0.2}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = VerifyEvidence.run(new String[] {"--evidence", dir.resolve("e.jws").toString(), "--capture",
            dir.resolve("c.jws").toString(), "--evidence-key", dir.resolve("ev.der").toString(), "--device-key",
            dir.resolve("dev.der").toString(), "--device-kid", MockFrameSource.KEY_ID, "--allow",
            dir.resolve("allow.json").toString(), "--server-key", dir.resolve("server.der").toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        String report = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "no server model given: re-scoring is skipped, which is itself a finding\n" + report);
        assertTrue(report.contains("NOT_RESCORED") && !report.contains("\"tamper\" : true"), report);
    }
}
