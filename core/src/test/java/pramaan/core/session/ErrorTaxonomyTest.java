/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.fusion.Veto;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;

/** Build Map #51: Bible Part 22 as executable rules. */
class ErrorTaxonomyTest {

    private SessionFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SessionFixtures();
    }

    @Test
    void everyFailureHasARowAndTheRowAgreesOnWhoPays() {
        for (FailureReason r : FailureReason.values()) {
            LivenessError e = LivenessError.of(r);
            assertEquals(r.consumesAttempt(), e.consumesAttempt(), r + " -> " + e);
        }
        for (LivenessError e : LivenessError.values()) {
            if (e != LivenessError.INVALID_FRAME) {
                assertTrue(e.messageId().orElseThrow().startsWith("pramaan."), e + " needs a message id");
            }
            if (e.handling() == LivenessError.Handling.FAIL_CLOSED) {
                assertEquals(LivenessError.Severity.HIGH, e.severity(), e + " fails closed and must alert");
            }
        }
        assertTrue(LivenessError.INVALID_FRAME.messageId().isEmpty(), "a skipped frame shows nothing");
    }

    @Test
    void deviceAndEngineErrorsFailWithoutCostingAnAttemptAndShowTheRightMessage() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        SessionEventRouter router = new SessionEventRouter(s);
        fx.toPassiveDue(s);
        router.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.DISCONNECTED, "cable", fx.clock.instant()));
        assertEquals(SessionState.IDLE, s.state());
        assertEquals("pramaan.error.device-unavailable", s.userMessageId().orElseThrow());

        fx.toPassiveDue(s);
        s.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        router.onCaptureFailed(new DeviceException(DeviceException.Reason.TIMEOUT, "slow"));
        assertEquals(FailureReason.DEVICE_TIMEOUT, s.lastFailure().orElseThrow());

        fx.toPassiveDue(s);
        router.onEngineError(new IllegalStateException("model runtime error"));
        assertEquals(FailureReason.ENGINE_ERROR, s.lastFailure().orElseThrow());
        assertEquals("pramaan.error.unavailable", s.userMessageId().orElseThrow());
        assertEquals(0, fx.registry.consumedAttempts("R1"));
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("LIVENESS_ERROR") && "HIGH".equals(e.fields().get("severity"))));
    }

    @Test
    void injectionIsAHardRejectWithAHighSeverityAudit() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.vetoed(Workflow.RESIDENT, Veto.INJECTION), null);
        assertEquals(FailureReason.REJECT_VETO, s.lastFailure().orElseThrow());
        assertEquals(1, fx.registry.consumedAttempts("R1"));
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("LIVENESS_ERROR")
                && "INJECTION_DETECTED".equals(e.fields().get("error")) && "INJECTION".equals(e.fields().get("vetoes"))));
    }

    @Test
    void aSecondFaceVetoIsAuditedAsWhatItIsNotAsAnInjection() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.vetoed(Workflow.RESIDENT, Veto.MULTIPLE_FACES), null);
        assertEquals(FailureReason.REJECT_VETO, s.lastFailure().orElseThrow());
        assertEquals(1, fx.registry.consumedAttempts("R1"));
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("LIVENESS_ERROR")
                && "VETO_MULTIPLE_FACES".equals(e.fields().get("error")) && "MULTIPLE_FACES".equals(e.fields().get("vetoes"))
                && "HIGH".equals(e.fields().get("severity"))));
        assertTrue(fx.audit.stream().noneMatch(e -> "INJECTION_DETECTED".equals(e.fields().get("error"))));
        for (Veto v : Veto.values()) {
            assertTrue(LivenessError.ofVetoes(EnumSet.of(v)).consumesAttempt(), v + " must cost an attempt like REJECT_VETO");
        }
    }

    @Test
    void aBindingFailureIsAHighSeverityReject() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        fx.bind(s, false);
        assertEquals(FailureReason.REJECT_BINDING, s.lastFailure().orElseThrow());
        assertTrue(fx.audit.stream().anyMatch(e -> "BINDING_FAILURE".equals(e.fields().get("error"))
                && "HIGH".equals(e.fields().get("severity"))));
    }

    @Test
    void tooManyUndecodableFramesBecomeADeviceErrorButOneIsJustSkipped() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        SessionEventRouter router = new SessionEventRouter(s);
        s.start();
        s.faceAcquired();
        for (int i = 0; i < 10; i++) {
            fx.clock.advanceMillis(100);
            router.onFrame(EvidenceResetTest.frameAt(fx.clock.instant(), i), i == 4 ? null : new byte[] {(byte) i}, 1);
        }
        assertEquals(SessionState.PASSIVE, s.state(), "one bad frame in ten is skipped");
        assertEquals(9, s.evidenceFrames(), "and never hashed");
        for (int i = 10; i < 14; i++) {
            fx.clock.advanceMillis(100);
            router.onFrame(EvidenceResetTest.frameAt(fx.clock.instant(), i), null, 1);
        }
        assertEquals(FailureReason.DEVICE_ERROR, s.lastFailure().orElseThrow(), "3 bad of the last 12 frames in 2 s is over 20%");
    }

    @Test
    void aClockThatRunsBackwardsFailsClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T09:00:00Z"));
        LivenessSession s = new LivenessSession(SessionContext.start(Workflow.RESIDENT, fx.policy, clock, new SecureRandom()),
                "R1", "OP1", "device-1", "centre-1", fx.registry, fx.audit::add, new SecureRandom());
        s.start();
        clock.now = clock.now.minusSeconds(30);
        assertEquals(SessionState.UNAVAILABLE, s.state());
        assertEquals(FailureReason.CLOCK_ANOMALY, s.lastFailure().orElseThrow());
        assertEquals(false, s.start(), "nothing proceeds on a clock that cannot be trusted");
    }

    @Test
    void aCorruptOrMismatchedModelPreventsAnySessionFromStarting() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);

        try (EngineBundle ok = EngineBundle.load(policy, tableFor(table, models, "smoke.onnx", model), Map.of("smoke.onnx", model))) {
            assertEquals(1, ok.models().size());
            assertEquals(SessionState.IDLE, ok.newSession(Workflow.RESIDENT, "R1", "OP1", "d", "c", new SessionRegistry(),
                    e -> { }, fx.clock, new SecureRandom()).state());
        }

        byte[] corrupt = model.clone();
        for (int i = 0; i < corrupt.length; i += 7) {
            corrupt[i] ^= 0x5A;
        }
        EngineUnavailableException broken = assertThrows(EngineUnavailableException.class,
                () -> EngineBundle.load(policy, tableFor(table, models, "smoke.onnx", corrupt), Map.of("smoke.onnx", corrupt)));
        assertEquals(LivenessError.BUNDLE_INVALID, broken.error());

        EngineUnavailableException swapped = assertThrows(EngineUnavailableException.class,
                () -> EngineBundle.load(policy, tableFor(table, models, "smoke.onnx", model), Map.of("smoke.onnx", corrupt)));
        assertEquals(LivenessError.BUNDLE_INVALID, swapped.error(), "table measured on another model");

        byte[] badPolicy = new String(policy, StandardCharsets.UTF_8).replace("minCues: 2", "minCues: \"2\"")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(LivenessError.BUNDLE_INVALID, assertThrows(EngineUnavailableException.class,
                () -> EngineBundle.load(badPolicy, tableFor(table, models, "smoke.onnx", model), Map.of("smoke.onnx", model))).error());
    }

    private static byte[] tableFor(String table, String modelsBlock, String name, byte[] model) {
        String replaced = table.replace(modelsBlock, "\"models\": {\"" + name + "\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        return replaced.getBytes(StandardCharsets.UTF_8);
    }

    /** A clock a test can turn back, which TestClock deliberately refuses to do. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void theDurationConstantsMatchTheBible() {
        assertEquals(Duration.ofSeconds(2), SessionEventRouter.BAD_FRAME_WINDOW);
        assertEquals(0.2, SessionEventRouter.BAD_FRAME_RATIO);
        assertTrue(EnumSet.allOf(LivenessError.class).size() >= 16, "every Part 22 row");
    }
}
