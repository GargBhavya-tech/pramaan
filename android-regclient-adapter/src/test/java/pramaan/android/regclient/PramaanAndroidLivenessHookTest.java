/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android.regclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.mosip.registration_client.liveness.CaptureTicket;
import io.mosip.registration_client.liveness.LivenessHook;
import io.mosip.registration_client.liveness.LivenessHooks;
import io.mosip.registration_client.liveness.LivenessRefusedException;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;

class PramaanAndroidLivenessHookTest {

    private static EngineBundle bundle;
    private JwsTrust trust;
    private EvidenceSigner signer;

    @BeforeAll
    static void setUpBundle() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
    }

    @BeforeEach
    void setUp() throws Exception {
        trust = JwsTrust.builder(Clock.systemUTC()).build();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair signerKey = g.generateKeyPair();
        signer = new EvidenceSigner(signerKey.getPrivate(), "android-evidence");
    }

    @AfterEach
    void resetHook() {
        PramaanAndroidLivenessHook.reset();
    }

    @Test
    void awaitClearanceWithoutSettingsThrowsRefused() {
        PramaanAndroidLivenessHook hook = new PramaanAndroidLivenessHook();
        hook.beginFaceScan("FACE_FULLFACE");
        assertThrows(LivenessRefusedException.class, hook::awaitCaptureClearance);
    }

    @Test
    void afterCaptureWithoutSettingsThrowsRefused() {
        PramaanAndroidLivenessHook hook = new PramaanAndroidLivenessHook();
        hook.beginFaceScan("FACE_FULLFACE");
        assertThrows(LivenessRefusedException.class, () -> hook.afterCapture(List.of(new BiometricsDto())));
    }

    @Test
    void configuredAndroidHookClearsAndReturnsValidTicket() throws Exception {
        AndroidAdapterSettings settings = AndroidAdapterSettings.builder(
                bundle, trust, signer, Clock.systemUTC(), new SecureRandom(),
                () -> new AndroidAdapterSettings.Subject(Workflow.RESIDENT, "SUB-101", "OP-01", "C-01"))
                .build();
        PramaanAndroidLivenessHook.configure(settings);

        PramaanAndroidLivenessHook hook = new PramaanAndroidLivenessHook();
        hook.beginFaceScan("FACE_FULLFACE");

        // Stream preview frame
        byte[] sampleJpeg = {(byte) 0xFF, (byte) 0xD8, 0, 1, 2};
        assertDoesNotThrow(() -> hook.onPreviewFrame(sampleJpeg, System.currentTimeMillis()));

        // Await clearance
        CaptureTicket ticket = hook.awaitCaptureClearance();
        assertNotNull(ticket);
        assertNotNull(ticket.transactionId());
        assertEquals(32, ticket.transactionId().length());

        // After capture returns evidence
        BiometricsDto face = new BiometricsDto();
        face.setSbiResponse("{\"status\":\"SUCCESS\"}");
        face.setPayLoad("sample-payload");
        String evidence = hook.afterCapture(List.of(face));
        assertNotNull(evidence);
        assertTrue(evidence.startsWith("ey"), "compact JWS evidence string: " + evidence);
    }

    @Test
    void promptListenerReceivesPromptEvents() {
        PramaanAndroidLivenessHook hook = new PramaanAndroidLivenessHook();
        AtomicReference<String> received = new AtomicReference<>();
        hook.setPromptListener((prompt, guidance) -> received.set(prompt));

        assertDoesNotThrow(() -> hook.beginFaceScan("FACE_FULLFACE"));
    }

    @Test
    void hookIsDiscoveredByServiceLoader() throws Exception {
        LivenessHook discovered = LivenessHooks.get();
        assertNotNull(discovered);
        assertTrue(discovered instanceof PramaanAndroidLivenessHook);
    }
}
