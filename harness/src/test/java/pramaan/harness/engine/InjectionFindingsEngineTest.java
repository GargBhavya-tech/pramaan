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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.SessionState;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceIdentity;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.HostCameraInventory;

/**
 * Bible Part 5, Build Map #84: the findings beyond the SBI device's own name — the operating system's camera list
 * and the device's digitalId — and what each does to a session under the policy switches that govern them.
 */
class InjectionFindingsEngineTest {

    private static EngineBundle bundle(String... replacements) throws Exception {
        String yaml = new String(Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml")), StandardCharsets.UTF_8);
        for (int i = 0; i < replacements.length; i += 2) {
            assertTrue(yaml.contains(replacements[i]), "the shipped policy no longer says: " + replacements[i]);
            yaml = yaml.replace(replacements[i], replacements[i + 1]);
        }
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        return EngineBundle.load(yaml.getBytes(StandardCharsets.UTF_8), bound.getBytes(StandardCharsets.UTF_8),
                Map.of("smoke.onnx", model));
    }

    private static DeviceCapabilities device(DeviceIdentity identity) {
        return new DeviceCapabilities(
                new DeviceMetadata("d", "0", "code", DeviceLevel.L1, "Logitech", "BRIO 4K", "SN", identity),
                EnumSet.noneOf(Capability.class));
    }

    private static HostCameraInventory cameras(String... names) {
        return () -> Optional.of(List.of(names));
    }

    private static LivenessEngineTest.Rig run(EngineBundle bundle, DeviceIdentity identity, HostCameraInventory host) {
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class), device(identity),
                host);
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        return rig;
    }

    @Test
    void anInstalledVirtualCameraIsRecordedButDoesNotRejectUnderTheShippedPolicy() throws Exception {
        LivenessEngineTest.Rig rig = run(bundle(), DeviceIdentity.UNCHECKED,
                cameras("Integrated Webcam", "OBS Virtual Camera"));
        assertEquals(List.of("OBS Virtual Camera"), rig.engine.injectionFindings().hostVirtualCameras());
        assertTrue(rig.engine.injectionFindings().hostEnumerated());
        assertFalse(rig.engine.boundVirtualCameraDetected(), "the SBI device itself is a Logitech");
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
    }

    @Test
    void whereTheCentreSwitchesItOnAnInstalledVirtualCameraIsAVeto() throws Exception {
        LivenessEngineTest.Rig rig = run(bundle("vetoOnHostVirtualCamera: false", "vetoOnHostVirtualCamera: true"),
                DeviceIdentity.UNCHECKED, cameras("Integrated Webcam", "ManyCam Virtual Webcam"));
        assertEquals(SessionState.RETRY_WAIT, rig.session.state(), rig.describe());
        assertTrue(rig.audits.stream().anyMatch(a -> a.contains("INJECTION_DETECTED")), rig.audits.toString());
    }

    @Test
    void aStationThatCannotListItsCamerasIsNotAccusedEvenWhenTheVetoIsOn() throws Exception {
        LivenessEngineTest.Rig rig = run(bundle("vetoOnHostVirtualCamera: false", "vetoOnHostVirtualCamera: true"),
                DeviceIdentity.UNCHECKED, Optional::empty);
        assertFalse(rig.engine.injectionFindings().hostEnumerated());
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
    }

    @Test
    void aDeviceThatSignsItsIdentityAndCannotProveItIsVetoedWhateverThePolicy() throws Exception {
        LivenessEngineTest.Rig rig = run(bundle(), DeviceIdentity.FAILED, cameras("Integrated Webcam"));
        assertEquals(SessionState.RETRY_WAIT, rig.session.state(), rig.describe());
        assertTrue(rig.audits.stream().anyMatch(a -> a.contains("INJECTION_DETECTED")), rig.audits.toString());
    }

    @Test
    void anUnregisteredDeviceIsAcceptedUnlessPolicyDemandsAVerifiedIdentity() throws Exception {
        assertEquals(SessionState.ACCEPTED, run(bundle(), DeviceIdentity.UNSIGNED, cameras()).session.state());
        EngineBundle strict = bundle("requireVerifiedDeviceIdentity: false", "requireVerifiedDeviceIdentity: true");
        assertEquals(SessionState.RETRY_WAIT, run(strict, DeviceIdentity.UNSIGNED, cameras()).session.state());
        assertEquals(SessionState.ACCEPTED, run(strict, DeviceIdentity.VERIFIED, cameras()).session.state());
    }

    @Test
    void aSoftwareCameraWithAnInnocentNameIsRecordedAndDoesNotRejectAnyone() throws Exception {
        HostCameraInventory renamed = new HostCameraInventory() {
            @Override
            public Optional<List<String>> cameraNames() {
                return Optional.of(List.of("Integrated Camera", "Logitech BRIO"));
            }

            @Override
            public Optional<List<String>> softwareCameras() {
                return Optional.of(List.of("Logitech BRIO"));
            }
        };
        LivenessEngineTest.Rig rig = run(bundle("vetoOnHostVirtualCamera: false", "vetoOnHostVirtualCamera: true"),
                DeviceIdentity.UNCHECKED, renamed);
        assertEquals(List.of("Logitech BRIO"), rig.engine.injectionFindings().hostSoftwareCameras());
        assertTrue(rig.engine.injectionFindings().hostVirtualCameras().isEmpty(), "the name fooled the denylist");
        assertEquals(SessionState.ACCEPTED, rig.session.state(), "recorded, not acted on: " + rig.describe());
    }

    @Test
    void timingIsCountedForTheRecordAndNeverRejectsAnyone() throws Exception {
        LivenessEngineTest.Rig rig = run(bundle(), DeviceIdentity.UNCHECKED, cameras());
        assertTrue(rig.engine.timing().frames() > 10);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
    }
}
