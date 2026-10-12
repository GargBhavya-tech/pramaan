/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.FusionResult;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.fusion.Veto;
import pramaan.core.fusion.ZoneDecider;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.FailureReason;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionEventRouter;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;
import pramaan.core.testing.TestClock;

/**
 * Build Map #51: every mock fault (#17) driven through the real router into a real session, each producing
 * exactly the outcome Bible Part 22 specifies, and none of them costing the resident an attempt.
 */
class FaultOutcomesTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

    private EngineBundle bundle;
    private TestClock clock;
    private SessionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
        clock = TestClock.at("2026-09-23T10:00:00Z");
        registry = new SessionRegistry();
    }

    /** What the desktop decoder would do: JPEG bytes decode, anything else does not. */
    private static byte[] decode(Frame f) {
        byte[] b = f.copyBytes();
        boolean jpeg = f.format() == PixelFormat.JPEG && b.length > 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8;
        return jpeg ? b : null;
    }

    private static final class Run {
        final LivenessSession session;
        final SessionEventRouter router;
        final MockFrameSource source;

        Run(LivenessSession session, SessionEventRouter router, MockFrameSource source) {
            this.session = session;
            this.router = router;
            this.source = source;
        }
    }

    private Run run(MockFaults faults, int steps, boolean faceSeen) throws Exception {
        MockFrameSource source = MockFrameSource.builder(clock).delayer(clock::advance).fps(10)
                .neutral(List.of(JPEG)).faults(faults).build();
        LivenessSession s = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", registry, e -> { }, clock,
                new SecureRandom());
        SessionEventRouter router = new SessionEventRouter(s);
        s.start();
        if (faceSeen) {
            s.faceAcquired();
        }
        source.start(null, new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                router.onFrame(frame, decode(frame), 1);
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
                router.onDeviceEvent(event);
            }
        });
        int stepped = 0;
        while (stepped < steps && source.step()) {
            stepped++;
        }
        return new Run(s, router, source);
    }

    @Test
    void aHealthyStreamBuildsEvidence() throws Exception {
        Run r = run(MockFaults.NONE, 20, true);
        assertEquals(SessionState.PASSIVE, r.session.state());
        assertEquals(20, r.session.evidenceFrames());
        assertFalse(r.session.lastFailure().isPresent());
    }

    @Test
    void aDisconnectIsADeviceErrorThatCostsNothing() throws Exception {
        Run r = run(MockFaults.builder().disconnectAfterFrames(12).build(), 30, true);
        assertEquals(SessionState.IDLE, r.session.state());
        assertEquals(FailureReason.DEVICE_ERROR, r.session.lastFailure().orElseThrow());
        assertEquals("pramaan.error.device-unavailable", r.session.userMessageId().orElseThrow());
        assertEquals(0, registry.consumedAttempts("R1"));
    }

    @Test
    void garbageFramesBecomeADeviceError() throws Exception {
        Run r = run(MockFaults.builder().corruptEvery(2).build(), 20, true);
        assertEquals(FailureReason.DEVICE_ERROR, r.session.lastFailure().orElseThrow());
        assertEquals(0, registry.consumedAttempts("R1"));
    }

    @Test
    void oneWrongFormatFrameIsSkippedAndNeverHashed() throws Exception {
        Run r = run(MockFaults.builder().wrongFormatAtFrame(5).build(), 20, true);
        assertEquals(SessionState.PASSIVE, r.session.state());
        assertEquals(19, r.session.evidenceFrames());
    }

    @Test
    void aLatencySpikeLongerThanTheTrackGapRestartsEvidence() throws Exception {
        Run r = run(MockFaults.builder().latencySpike(8, Duration.ofMillis(500)).build(), 20, true);
        assertEquals(SessionState.ACQUIRING, r.session.state());
        assertEquals(0, r.session.evidenceFrames(), "nothing from before the gap is kept");
    }

    @Test
    void anEmptyStreamTimesOutAsNoFaceWithoutCostingAnAttempt() throws Exception {
        Run r = run(MockFaults.builder().emptyStream().build(), 210, false);
        assertEquals(SessionState.IDLE, r.session.state());
        assertEquals(FailureReason.NO_USABLE_FACE, r.session.lastFailure().orElseThrow());
        assertEquals("pramaan.guidance.look-at-camera", r.session.userMessageId().orElseThrow());
        assertEquals(0, registry.consumedAttempts("R1"));
    }

    @Test
    void aCaptureErrorIsADeviceErrorThatCostsNothing() throws Exception {
        Run r = run(MockFaults.builder().captureError().build(), 20, true);
        FusionResult fused = new RiskFusion(bundle.table()).fuse(
                List.of(CueResult.vote("passive", 0.99, 0), CueResult.vote("moire", 0.99, 0)), Workflow.RESIDENT, DeviceLevel.L1);
        Decision decision = new ZoneDecider(() -> 0.999, bundle.table().disagreementLogitGap()).decide(fused,
                bundle.table().operatingPoint("resident-default", "default"), bundle.policy().profile(Workflow.RESIDENT),
                EnumSet.noneOf(Veto.class));
        assertTrue(r.session.passiveDecided(decision, null));
        assertEquals(SessionState.CAPTURING, r.session.state());
        DeviceException e = assertThrows(DeviceException.class, () -> r.source.capture(
                new CaptureRequest(r.session.nonce().value(), "", 40, Duration.ofSeconds(10))));
        r.router.onCaptureFailed(e);
        assertEquals(SessionState.IDLE, r.session.state());
        assertEquals(FailureReason.DEVICE_ERROR, r.session.lastFailure().orElseThrow());
        assertEquals(0, registry.consumedAttempts("R1"));
    }
}
