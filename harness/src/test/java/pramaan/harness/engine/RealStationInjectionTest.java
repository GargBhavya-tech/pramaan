/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import pramaan.core.source.InjectionGuard;
import pramaan.desktop.capture.WindowsCameraInventory;

/**
 * The injection veto against this machine's REAL camera list, not a made-up one: if Windows lists a virtual camera (OBS Virtual
 * Camera, ManyCam, and so on) the engine records it under the shipped policy and, where the centre switches
 * {@code vetoOnHostVirtualCamera} on, rejects the session. Skipped everywhere a virtual camera is not actually registered (CI,
 * and this machine until OBS's virtual camera has been started), so it never fails for want of one. The printed block is the
 * evidence: it is what docs/media/injection-veto.png is rendered from.
 */
class RealStationInjectionTest {

    private static EngineBundle bundle(boolean veto) throws Exception {
        String yaml = new String(Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml")), StandardCharsets.UTF_8);
        String off = "vetoOnHostVirtualCamera: false";
        assertTrue(yaml.contains(off), "the shipped policy no longer says: " + off);
        if (veto) {
            yaml = yaml.replace(off, "vetoOnHostVirtualCamera: true");
        }
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        return EngineBundle.load(yaml.getBytes(StandardCharsets.UTF_8), bound.getBytes(StandardCharsets.UTF_8),
                Map.of("smoke.onnx", model));
    }

    private static LivenessEngineTest.Rig run(EngineBundle bundle, HostCameraInventory host) {
        DeviceCapabilities device = new DeviceCapabilities(
                new DeviceMetadata("d", "0", "code", DeviceLevel.L1, "Logitech", "BRIO 4K", "SN", DeviceIdentity.UNCHECKED),
                EnumSet.noneOf(Capability.class));
        LivenessEngineTest.Rig rig = new LivenessEngineTest.Rig(bundle, 10, EnumSet.noneOf(Capability.class), device, host);
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        return rig;
    }

    @Test
    void aVirtualCameraThatWindowsReallyListsIsVetoedWhereTheCentreSwitchesItOn() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "the real camera list is read on Windows");
        WindowsCameraInventory real = new WindowsCameraInventory();
        Optional<HostCameraInventory.Cameras> seen = real.read();
        assumeTrue(seen.isPresent(), "this machine would not list its cameras");
        List<String> virtual = InjectionGuard.hostVirtualCameras(seen.get().names(), InjectionGuard.KNOWN_VIRTUAL_CAMERA_PRODUCTS);
        assumeTrue(!virtual.isEmpty(), "no virtual camera is registered here: " + seen.get().names());

        LivenessEngineTest.Rig recorded = run(bundle(false), real);
        LivenessEngineTest.Rig vetoed = run(bundle(true), real);

        System.out.println("REAL STATION INJECTION CHECK");
        System.out.println("  cameras Windows lists        : " + seen.get().names());
        System.out.println("  software cameras among them  : " + seen.get().software());
        System.out.println("  matched the virtual-camera list: " + virtual);
        System.out.println("  shipped policy (veto off)    : recorded " + recorded.engine.injectionFindings().hostVirtualCameras()
                + ", session " + recorded.session.state());
        System.out.println("  centre switches the veto on  : session " + vetoed.session.state() + ", audit "
                + vetoed.audits.stream().filter(a -> a.contains("INJECTION_DETECTED")).findFirst().orElse("(none)"));

        assertEquals(virtual, recorded.engine.injectionFindings().hostVirtualCameras());
        assertEquals(SessionState.ACCEPTED, recorded.session.state(), recorded.describe());
        assertEquals(SessionState.RETRY_WAIT, vetoed.session.state(), vetoed.describe());
        assertTrue(vetoed.audits.stream().anyMatch(a -> a.contains("INJECTION_DETECTED")), vetoed.audits.toString());
    }
}
