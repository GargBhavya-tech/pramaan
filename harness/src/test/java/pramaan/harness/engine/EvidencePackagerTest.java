/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.evidence.EvidencePackager;
import pramaan.core.evidence.EvidenceRecord;
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.SessionState;
import pramaan.core.source.Capability;
import pramaan.desktop.ImageIoJpegEncoder;

/**
 * Bible Part 14; Build Map #59-#61, ADR 0003 patch P4: the evidence packager on a real, engine-produced accepted
 * attempt — cue results, decision, binding, sample crops.
 */
class EvidencePackagerTest {

    private static EngineBundle bundle;
    private static KeyPair signerKey;
    private static KeyPair serverKey;

    @BeforeAll
    static void setup() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        signerKey = g.generateKeyPair();
        serverKey = g.generateKeyPair();
    }

    private static EvidencePackager packager() {
        return new EvidencePackager(new EvidenceSigner(signerKey.getPrivate(), "evidence"), serverKey.getPublic(),
                new ImageIoJpegEncoder(), new SecureRandom());
    }

    @Test
    void anAcceptedAttemptPacksSignedEvidenceWithItsDecisionAndBindingAndIds() throws Exception {
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());

        EvidencePackager.Packet packet = packager().pack(rig.engine, "OP1", "c1", Instant.parse("2026-09-26T10:00:00Z"));

        assertTrue(packet.evidenceJws().startsWith("ey"), "a compact JWS");
        EvidenceRecord verified = EvidenceSigner.verify(packet.evidenceJws(),
                pramaan.core.crypto.JwsTrust.builder(java.time.Clock.systemUTC()).key("evidence", signerKey.getPublic()).build());
        assertEquals("OP1", verified.json().path("operatorId").asText());
        assertEquals("c1", verified.json().path("centreId").asText());
        assertEquals("ACCEPTED", verified.json().path("outcome").asText());
        assertTrue(verified.json().path("cues").has("passive"), verified.json().toString());
        assertTrue(verified.json().has("binding"), verified.json().toString());
        assertTrue(verified.json().has("fusion"), verified.json().toString());
        assertEquals("denylist+host+identity", verified.json().path("policy").path("injection").path("guard").asText());
        assertFalse(verified.json().path("policy").path("injection").path("detected").asBoolean(),
                "the mock's own device (PRAMAAN/MockFace) is not on the denylist");
        assertEquals(packet.capture().signedData(), rig.engine.acceptedCapture().orElseThrow().signedData());
    }

    @Test
    void packingBeforeAnAcceptRefuses() {
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class));
        // No frames fed yet: the session is nowhere near ACCEPTED.
        assertThrows(IllegalStateException.class,
                () -> packager().pack(rig.engine, "OP1", "c1", Instant.parse("2026-09-26T10:00:00Z")));
    }

    @Test
    void withNoServerKeyPackingStillSucceedsAndRecordsNoSamples() throws Exception {
        // The scripted resident (SimulatedResident) never supplies a sample crop, so this also covers the case
        // where there is nothing to encrypt: samples() must be absent, never a null-key crash.
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());

        EvidencePackager noCrops = new EvidencePackager(new EvidenceSigner(signerKey.getPrivate(), "evidence"), null,
                new ImageIoJpegEncoder(), new SecureRandom());
        EvidencePackager.Packet packet = noCrops.pack(rig.engine, "OP1", "c1", Instant.parse("2026-09-26T10:00:00Z"));
        assertTrue(!packet.record().json().has("samples"), packet.record().json().toString());
    }
}
