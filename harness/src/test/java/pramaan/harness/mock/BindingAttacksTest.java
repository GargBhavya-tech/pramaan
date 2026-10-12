/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.binding.BindingInput;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.CaptureBinder;
import pramaan.core.binding.Embeddings;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.FusionResult;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.fusion.Veto;
import pramaan.core.fusion.ZoneDecider;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.policy.OperatingPoint;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.FailureReason;
import pramaan.core.session.FrameHashChain;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionEventRouter;
import pramaan.core.session.SessionNonce;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.Capability;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;

/**
 * Build Map #58: the capture-swap attacks, end to end through the mock device's real signatures, the real
 * router, session and binder. Each is rejected, with the reason code that names what was attacked.
 */
class BindingAttacksTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    private static final float[] RESIDENT = face(0);
    private static final float[] SOMEONE_ELSE = face(64);

    private EngineBundle bundle;
    private TestClock clock;
    private MockFrameSource source;
    private LivenessSession session;
    private SessionEventRouter router;
    private CaptureBinder binder;
    private final List<byte[]> hashedFrames = new ArrayList<>();
    private final List<Long> hashedTimes = new ArrayList<>();

    private static float[] face(int at) {
        float[] v = new float[128];
        Arrays.fill(v, 0.01f);
        v[at] = 1;
        return Embeddings.normalize(v);
    }

    @BeforeEach
    void setUp() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
        clock = TestClock.at("2026-09-23T10:00:00Z");
        source = MockFrameSource.builder(clock).delayer(clock::advance).fps(10).neutral(List.of(JPEG)).build();
        session = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", new SessionRegistry(), e -> { }, clock,
                new SecureRandom());
        router = new SessionEventRouter(session);
        binder = new CaptureBinder(source.trust(), bundle.table().thetaSame(),
                bundle.policy().sessionTimeouts().bindingWindow(), bundle.policy().sessionTimeouts().captureMaxSkew());
        source.start(null, new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                long before = session.evidenceFrames();
                router.onFrame(frame, frame.copyBytes(), 1);
                if (session.evidenceFrames() > before) {
                    hashedFrames.add(frame.copyBytes());
                    hashedTimes.add(frame.hostArrival().toEpochMilli());
                    if (session.state() != SessionState.CAPTURING) {
                        session.recordEmbedding(RESIDENT);
                    }
                }
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
                router.onDeviceEvent(event);
            }
        });
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            source.step();
        }
    }

    private Decision decide(boolean afterChallenge, CueResult... cues) {
        FusionResult f = new RiskFusion(bundle.table()).fuse(List.of(cues), Workflow.RESIDENT, DeviceLevel.L1);
        ZoneDecider d = new ZoneDecider(() -> 0.999, bundle.table().disagreementLogitGap());
        OperatingPoint point = bundle.table().operatingPoint("resident-default", "default");
        return afterChallenge ? d.decideAfterChallenge(f, point, bundle.policy().profile(Workflow.RESIDENT), EnumSet.noneOf(Veto.class))
                : d.decide(f, point, bundle.policy().profile(Workflow.RESIDENT), EnumSet.noneOf(Veto.class));
    }

    /** A genuine attempt up to CAPTURING: 2 s of frames, then a passive accept. */
    private void passiveAcceptToCapturing() {
        session.start();
        session.faceAcquired();
        frames(20);
        assertTrue(session.passiveDecided(decide(false, CueResult.vote("passive", 0.99, 0), CueResult.vote("moire", 0.99, 0)), null));
        assertEquals(SessionState.CAPTURING, session.state());
    }

    /** A genuine attempt that goes through a two-step challenge before capture. */
    private void challengeToCapturing() {
        session.start();
        session.faceAcquired();
        frames(20);
        SessionCapabilities caps = SessionCapabilities.of(10, new DeviceCapabilities(
                new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1"), EnumSet.noneOf(Capability.class)));
        ChallengePlan plan = ChallengeGenerator.fresh(new SecureRandom()).plan(List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED),
                2, 2, caps, AccessibilityAdjustments.NONE);
        assertTrue(session.passiveDecided(decide(false, CueResult.vote("passive", 0.62, 0), CueResult.vote("colorTexture", 0.40, 0),
                CueResult.vote("moire", 0.70, 0)), plan));
        assertEquals(SessionState.CHALLENGING, session.state());
        for (int step = 0; step < 2; step++) {
            frames(10);
            session.challengeStepPassed();
        }
        frames(3);
        assertTrue(session.challengesDecided(decide(true, CueResult.vote("passive", 0.99, 0), CueResult.vote("moire", 0.99, 0),
                CueResult.vote("flatness", 0.99, 0))));
        assertEquals(SessionState.CAPTURING, session.state());
    }

    private SignedCapture capture(CaptureRequest request) throws Exception {
        SignedCapture c = source.capture(request);
        session.captured();
        return c;
    }

    private void verdict(BindingInput input) {
        session.bindingVerified(binder.bind(input));
    }

    private void assertRejectedFor(String lockCode) {
        assertEquals(FailureReason.REJECT_BINDING, session.lastFailure().orElseThrow());
        assertEquals(SessionState.RETRY_WAIT, session.state(), "a binding failure costs an attempt");
        assertEquals(List.of(lockCode), session.bindingFailures(), "exactly this reason, nothing else");
    }

    @Test
    void theGenuineAttemptBinds() throws Exception {
        challengeToCapturing();
        SignedCapture c = capture(session.captureRequest("", 40, Duration.ofSeconds(10)));
        verdict(session.bindingInput(c, RESIDENT, 1, List.of()));
        assertEquals(SessionState.ACCEPTED, session.state(), String.valueOf(session.bindingFailures()));
        assertNotNull(session.bindingFinalHash());
    }

    @Test
    void passingTheChallengesThenReturningADifferentFaceIsRejected() throws Exception {
        challengeToCapturing();
        SignedCapture c = capture(session.captureRequest("", 40, Duration.ofSeconds(10)));
        verdict(session.bindingInput(c, SOMEONE_ELSE, 1, List.of()));
        assertRejectedFor("identity:IDENTITY_MISMATCH");
    }

    @Test
    void returningAnOldSignedCaptureIsRejected() throws Exception {
        // A genuine, correctly signed capture from an earlier attempt, replayed into this one.
        SignedCapture old = source.capture(new CaptureRequest(SessionNonce.generate(new SecureRandom()).value(), "", 40,
                Duration.ofSeconds(10)));
        passiveAcceptToCapturing();
        session.captureRequest("", 40, Duration.ofSeconds(10));
        session.captured();
        verdict(session.bindingInput(old, RESIDENT, 1, List.of()));
        assertRejectedFor("nonce:NONCE_MISMATCH");
    }

    @Test
    void droppingAFrameFromTheChainIsRejected() throws Exception {
        passiveAcceptToCapturing();
        SignedCapture c = capture(session.captureRequest("", 40, Duration.ofSeconds(10)));
        BindingInput honest = session.bindingInput(c, RESIDENT, 1, List.of());
        // The same frames with one removed: a chain head that is not this attempt's.
        FrameHashChain edited = new FrameHashChain(session.nonce());
        for (int i = 0; i < hashedFrames.size(); i++) {
            if (i != 7) {
                edited.append(hashedFrames.get(i), hashedTimes.get(i));
            }
        }
        BindingInput tampered = new BindingInput(honest.nonce(), edited.head(), honest.evidenceTrackId(), honest.currentTrackId(),
                honest.lastEvidenceFrameAt(), honest.captureRequestedAt(), honest.referenceEmbeddings(), honest.captureEmbedding(),
                honest.capture(), honest.earlierCaptures());
        BindingResult r = binder.bind(tampered);
        assertTrue(r.passed(), "every lock passes on the edited evidence...");
        session.bindingVerified(r);
        assertRejectedFor("attempt:EVIDENCE_CHAIN_MISMATCH");
    }

    @Test
    void delayingTheCaptureBy5SecondsIsRejected() throws Exception {
        passiveAcceptToCapturing();
        clock.advance(Duration.ofSeconds(5)); // the preview freezes; nothing is verified while the capture waits
        SignedCapture c = capture(session.captureRequest("", 40, Duration.ofSeconds(10)));
        verdict(session.bindingInput(c, RESIDENT, 1, List.of()));
        assertRejectedFor("time:TIME_GAP");
    }

    @Test
    void breakingTheSbiCaptureChainIsRejected() throws Exception {
        // The registration session's earlier capture (say, an earlier biometric); the face capture should chain onto it.
        SignedCapture earlier = source.capture(new CaptureRequest(SessionNonce.generate(new SecureRandom()).value(), "", 40,
                Duration.ofSeconds(10)));
        passiveAcceptToCapturing();
        SignedCapture restarted = capture(session.captureRequest("", 40, Duration.ofSeconds(10)));
        verdict(session.bindingInput(restarted, RESIDENT, 1, List.of(earlier)));
        assertRejectedFor("chain:CHAIN_BROKEN");
    }

    @Test
    void theSbiChainLinksWhenTheCaptureChainsOntoTheEarlierOne() throws Exception {
        SignedCapture earlier = source.capture(new CaptureRequest(SessionNonce.generate(new SecureRandom()).value(), "", 40,
                Duration.ofSeconds(10)));
        passiveAcceptToCapturing();
        SignedCapture chained = capture(session.captureRequest(earlier.chainHash(), 40, Duration.ofSeconds(10)));
        verdict(session.bindingInput(chained, RESIDENT, 1, List.of(earlier)));
        assertEquals(SessionState.ACCEPTED, session.state(), String.valueOf(session.bindingFailures()));
    }

    @Test
    void theCaptureCanBeRequestedOnlyOncePerAttempt() {
        passiveAcceptToCapturing();
        session.captureRequest("", 40, Duration.ofSeconds(10));
        assertThrows(IllegalStateException.class, () -> session.captureRequest("", 40, Duration.ofSeconds(10)));
    }
}
