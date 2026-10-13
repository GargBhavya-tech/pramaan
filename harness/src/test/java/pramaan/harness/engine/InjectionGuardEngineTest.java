/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.SessionState;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;

/**
 * Bible Part 5; Build Map #84: a device bound to the capture path whose reported make/model matches the
 * virtual-camera denylist is refused by {@code Veto.INJECTION} — end to end through a real engine, not just the
 * {@code InjectionGuard} utility.
 */
class InjectionGuardEngineTest {

    private static EngineBundle bundle;

    @BeforeAll
    static void loadBundle() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
        assertTrue(bundle.policy().vetoOnBoundVirtualCamera(), "the shipped policy vetoes a bound virtual camera");
    }

    private static DeviceCapabilities device(String make, String model) {
        return new DeviceCapabilities(new DeviceMetadata("d", "0", "code", DeviceLevel.L1, make, model, "SN"),
                EnumSet.noneOf(Capability.class));
    }

    @Test
    void aDenylistedDeviceIsVetoedBeforeAnyFramesAreEvenAnalysed() {
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class),
                device("OBS Virtual Camera", "x"));
        assertTrue(rig.engine.boundVirtualCameraDetected());
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        // Not RETRY_WAIT/LOCKED_OUT from a PAD score: the veto rejects before fusion even runs.
        assertEquals(SessionState.RETRY_WAIT, rig.session.state(), rig.describe());
        assertTrue(rig.audits.stream().anyMatch(a -> a.contains("INJECTION_DETECTED")), rig.audits.toString());
    }

    @Test
    void aGenuineDeviceIsNotVetoedAndTheSessionAccepts() {
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class),
                device("Logitech", "BRIO 4K"));
        assertFalse(rig.engine.boundVirtualCameraDetected());
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
    }
}
