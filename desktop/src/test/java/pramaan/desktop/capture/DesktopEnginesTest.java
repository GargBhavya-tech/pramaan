/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.Workflow;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionContext;
import pramaan.core.session.SessionRegistry;
import pramaan.core.source.Capability;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.SignedCapture;

/** The scaffold's engine on the real, gitignored models: the checked-in table must bind exactly them. */
class DesktopEnginesTest {

    private static final Path CONFIG = Path.of("..", "config");
    private static final Path MODELS = Path.of("..", "models");

    private static boolean modelsPresent() throws Exception {
        for (String name : OperatingPointTable.modelNames(Files.readAllBytes(CONFIG.resolve("operating-points.json")), "table")) {
            if (!Files.exists(MODELS.resolve(name))) {
                return false;
            }
        }
        return true;
    }

    @Test
    void theRealBundleLoadsAndBuildsAnEngineForTheMeasuredFrameRate() throws Exception {
        assumeTrue(modelsPresent(), "models/ not downloaded (gitignored)");
        try (EngineBundle bundle = DesktopEngines.loadBundle(CONFIG, MODELS)) {
            assertEquals(OperatingPointTable.modelNames(Files.readAllBytes(CONFIG.resolve("operating-points.json")), "t"),
                    bundle.models().keySet(), "exactly the table's models");
            assertTrue(bundle.models().containsKey(pramaan.core.engine.StandardEngines.BLENDSHAPES), "blendshapes are wired (ADR 0002)");
            JwsTrust trust = JwsTrust.builder(Clock.systemUTC())
                    .key("k", KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic()).build();
            DesktopEngines engines = new DesktopEngines(bundle, trust, new SecureRandom());
            LivenessSession session = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "dev", "c1", new SessionRegistry(),
                    e -> { }, Clock.systemUTC(), new SecureRandom());
            DeviceCapabilities device = new DeviceCapabilities(
                    new DeviceMetadata("d", "0", "code", DeviceLevel.L1, "make", "model", "1"), EnumSet.noneOf(Capability.class));
            LivenessEngine engine = engines.factory(session, NO_DEVICE, EngineListener.NONE, 40)
                    .create(SessionCapabilities.of(3, device), device);
            assertNotNull(engine);
        }
    }

    @Test
    void aMissingModelOrAMalformedTableStopsTheEngineBeforeAnySessionExists(@TempDir Path empty) throws Exception {
        assertThrows(NoSuchFileException.class, () -> DesktopEngines.loadBundle(CONFIG, empty));
        Path config = empty.resolve("config");
        Files.createDirectories(config);
        Files.copy(CONFIG.resolve("pramaan-policy.yaml"), config.resolve("pramaan-policy.yaml"));
        Files.writeString(config.resolve("operating-points.json"), "{\"tableVersion\": 2}");
        assertThrows(RuntimeException.class, () -> DesktopEngines.loadBundle(config, empty),
                "a malformed table is refused before any model is read");
    }

    private static final FaceFrameSource NO_DEVICE = new FaceFrameSource() {
        @Override
        public DeviceCapabilities discover() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start(SessionContext session, FrameSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SignedCapture capture(CaptureRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stop() {
        }
    };
}
