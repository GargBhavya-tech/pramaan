/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.Workflow;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.CaptureBinder;
import pramaan.core.binding.Embeddings;
import pramaan.core.crypto.JwsTrust;
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
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;
import pramaan.core.transparency.AttemptEntry;
import pramaan.core.transparency.MerkleLog;
import pramaan.desktop.ImageIoJpegEncoder;
import pramaan.harness.mock.MockFrameSource;

/**
 * Build Map #63: an attack the client's weak model accepted in "2026" is flagged when the stored evidence is
 * rescanned with a stronger model in "2028"; the genuine enrolment stays confirmed. Both attempts go into a
 * transparency log (#64), and each can be proven included.
 */
class RetroactiveRescanTest {

    static KeyPair clientKey;
    static KeyPair serverKey;
    private static final float[] FACE = face();

    private EngineBundle bundle;
    private TestClock clock;
    private MockFrameSource device;
    private LivenessSession current;
    private SessionEventRouter router;
    private final MerkleLog log = MerkleLog.inMemory();
    private final List<byte[]> logged = new ArrayList<>();

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

    /** A crop the "strong model" recognises as an attack: a red cast, standing in for a replay artefact. */
    private static RgbImage crop(boolean attack) {
        byte[] px = new byte[112 * 112 * 3];
        for (int p = 0; p < 112 * 112; p++) {
            int x = p % 112;
            int y = p / 112;
            px[3 * p] = (byte) (attack ? 220 : 90 + x / 2);
            px[3 * p + 1] = (byte) (70 + y / 2);
            px[3 * p + 2] = (byte) (110 + (x + y) / 4);
        }
        return RgbImage.of(112, 112, px);
    }

    private static double meanRed(RgbImage img) {
        double s = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                s += img.get(x, y, 0);
            }
        }
        return s / (img.width() * img.height());
    }

    /** One device for the whole day; its frames go to whichever session is current. */
    private void startDevice() throws Exception {
        clock = TestClock.at("2026-09-23T10:00:00Z");
        device = MockFrameSource.builder(clock).delayer(clock::advance).fps(10)
                .neutral(List.of(new byte[] {(byte) 0xFF, (byte) 0xD8, 1})).build();
        device.start(null, new FrameSink() {
            @Override
            public void onFrame(Frame f) {
                router.onFrame(f, f.copyBytes(), 1);
                if (current.state() == SessionState.PASSIVE) {
                    current.recordEmbedding(FACE);
                }
            }

            @Override
            public void onDeviceEvent(DeviceEvent e) {
                if (router != null) {
                    router.onDeviceEvent(e);
                }
            }
        });
    }

    /** One session the client accepts; its evidence and capture are stored under {@code id}. */
    private void storeAcceptedSession(Path dir, String id, boolean attack) throws Exception {
        current = bundle.newSession(Workflow.RESIDENT, id, "OP1", "mock", "c1", new SessionRegistry(), e -> { }, clock,
                new SecureRandom());
        router = new SessionEventRouter(current);
        current.start();
        current.faceAcquired();
        for (int i = 0; i < 20; i++) {
            device.step();
        }
        // The weak 2026 model scores the attack as live too: the client accepts both.
        FusionResult fused = new RiskFusion(bundle.table()).fuse(
                List.of(CueResult.vote("passive", 0.97, 0), CueResult.vote("moire", 0.95, 0)), Workflow.RESIDENT, DeviceLevel.L1);
        Decision d = new ZoneDecider(() -> 0.999, bundle.table().disagreementLogitGap()).decide(fused,
                bundle.table().operatingPoint("resident-default", "default"), bundle.policy().profile(Workflow.RESIDENT),
                EnumSet.noneOf(Veto.class));
        current.passiveDecided(d, null);
        SignedCapture capture = device.capture(current.captureRequest("", 40, Duration.ofSeconds(10)));
        current.captured();
        BindingResult br = new CaptureBinder(device.trust(), bundle.table().thetaSame(),
                bundle.policy().sessionTimeouts().bindingWindow(), bundle.policy().sessionTimeouts().captureMaxSkew())
                .bind(current.bindingInput(capture, FACE, 1, List.of()));
        current.bindingVerified(br);
        assertEquals(SessionState.ACCEPTED, current.state());
        List<byte[]> jpegs = new ArrayList<>();
        ImageIoJpegEncoder jpeg = new ImageIoJpegEncoder();
        for (int i = 0; i < 3; i++) {
            jpegs.add(jpeg.encode(crop(attack)));
        }
        EvidenceRecord record = EvidenceBuilder.forSession(current, bundle).device(capture.device()).fusion(d).binding(br)
                .operator("OP1", "c1").samples(SampleCrypto.encrypt(jpegs, serverKey.getPublic(), new SecureRandom()))
                .build(clock.instant());
        Files.writeString(dir.resolve(id + ".evidence.jws"), new EvidenceSigner(clientKey.getPrivate(), "evidence").sign(record));
        Files.writeString(dir.resolve(id + ".capture.jws"), capture.signedData());
        byte[] entry = AttemptEntry.of(current, record);
        log.append(entry);
        logged.add(entry);
    }

    private RecheckVerifier verifier(CropScorer scorer) {
        return new RecheckVerifier(JwsTrust.builder(TestClock.at("2026-09-23T10:00:00Z")).key("evidence", clientKey.getPublic()).build(),
                device.trust(), Set.copyOf(bundle.table().models().values()), Set.of(bundle.policySha256()), serverKey.getPrivate(),
                scorer, 0.2);
    }

    @Test
    void anAttackMissedIn2026IsFoundByThe2028Model(@TempDir Path dir) throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
        startDevice();
        storeAcceptedSession(dir, "enrolment-genuine", false);
        storeAcceptedSession(dir, "enrolment-attack", true);

        CropScorer weak2026 = c -> 0.9;
        CropScorer strong2028 = c -> meanRed(c) > 180 ? 0.03 : 0.93;

        JsonNode then = RetroactiveRescan.toReviewList(RetroactiveRescan.scan(dir, verifier(weak2026)));
        assertEquals(2, then.path("scanned").asInt());
        assertEquals(0, then.path("toReview").size(), "the 2026 model confirmed both: " + then);

        JsonNode now = RetroactiveRescan.toReviewList(RetroactiveRescan.scan(dir, verifier(strong2028)));
        assertEquals(1, now.path("toReview").size(), now.toString());
        JsonNode flagged = now.path("toReview").get(0);
        assertEquals("enrolment-attack", flagged.path("id").asText());
        assertEquals("ADJUDICATE", flagged.path("route").asText());
        assertTrue(flagged.toString().contains("CLIENT_SERVER_DISAGREEMENT"), flagged.toString());

        // Both attempts are in the transparency log, and each can be proven included.
        assertEquals(2, log.size());
        for (int i = 0; i < logged.size(); i++) {
            assertTrue(MerkleLog.verifyInclusion(MerkleLog.leafHash(logged.get(i)), i, log.size(), log.inclusionProof(i, log.size()),
                    log.root()), "attempt " + i + " provably in the log");
        }
    }
}
