/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.binding.Embeddings;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.engine.CaptureAnalyser;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.FrameAnalyser;
import pramaan.core.engine.FrameObservation;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.gate.FaceGate;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.quality.Guidance;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.FailureReason;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.session.Transition;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.Frame;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.testing.TestClock;
import pramaan.harness.engine.SimulatedResident.Behaviour;
import pramaan.harness.mock.MockFrameSource;

/**
 * The engine loop end to end (Build Map Phase 11): real session, fusion, generator, detectors, latency rule,
 * binder and the mock device's real signatures. Perception is a scripted resident who renders 3-D landmarks
 * and answers the prompts the engine shows, so every step the engine takes is driven by what it asked for.
 */
class LivenessEngineTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    private static final float[] RESIDENT = SimulatedResident.RESIDENT;
    private static final float[] SOMEONE_ELSE = SimulatedResident.embedding(64);
    private static EngineBundle bundle;

    @BeforeAll
    static void loadBundle() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
    }

    // ------------------------------------------------------------------------------------------ scenarios

    @Test
    void aGenuineResidentIsAcceptedAndBound() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(400);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
        assertTrue(rig.engine.acceptedCapture().isPresent());
        assertNotNull(rig.session.bindingFinalHash());
        assertTrue(rig.states.containsAll(List.of(SessionState.ACQUIRING, SessionState.PASSIVE, SessionState.CAPTURING,
                SessionState.BINDING, SessionState.ACCEPTED)), String.valueOf(rig.states));
        assertEquals(Guidance.NONE, rig.guidance.get(rig.guidance.size() - 1));
    }

    @Test
    void theGuaranteedThreeFramesPerSecondStillReachesADecision() {
        // At 3 fps frames are 333 ms apart. With the fixed 300 ms track gap every frame ended the track, the
        // evidence restarted on every frame, and no desktop session could ever get past PASSIVE.
        Rig rig = new Rig(3, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.run(200);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
        assertFalse(rig.session.history().stream().anyMatch(t -> t.why().contains("face lost")), rig.describe());
    }

    @Test
    void aPrintedPhotoIsRejectedWithoutAChallengeToPractiseOn() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.03;
        rig.resident.moire = 0.10;
        rig.run(400);
        assertEquals(FailureReason.REJECT_PAD, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertTrue(rig.prompts.isEmpty(), "a clear attack gets no challenge");
        assertFalse(rig.states.contains(SessionState.CAPTURING));
    }

    @Test
    void aStillPhotoCannotAnswerAChallenge() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.behaviour = Behaviour.STILL;
        rig.run(400);
        assertEquals(FailureReason.CHALLENGE_TIMEOUT, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertEquals(1, rig.prompts.size(), "one prompt, never answered");
    }

    @Test
    void doingSomethingElseIsAWrongAction() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.behaviour = Behaviour.CONTRARY;
        rig.run(400);
        assertEquals(FailureReason.CHALLENGE_FAILED, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertTrue(rig.audits.stream().anyMatch(a -> a.contains("WRONG_ACTION")), String.valueOf(rig.audits));
    }

    @Test
    void anActionAlreadyUnderWayAtThePromptIsRepromptedOnceThenFailed() {
        // A pre-timed replay: the "response" begins 20 ms after the prompt, faster than any person.
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.behaviour = Behaviour.EARLY;
        rig.run(400);
        assertEquals(FailureReason.CHALLENGE_FAILED, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertTrue(rig.audits.stream().anyMatch(a -> a.startsWith("CHALLENGE_REPROMPT")), String.valueOf(rig.audits));
        assertTrue(rig.prompts.stream().filter(p -> p.startsWith("0:")).count() >= 2, "step 0 was shown again: " + rig.prompts);
    }

    @Test
    void aBorderlineGenuineResidentPassesBecauseEveryChallengeUnlocksACue() {
        // Part 9 re-fuses with what the challenge unlocked (turn -> flatness, smile -> deformation), and Part 10's
        // cue-unlocking puts one such step in every plan. Before it, a plan of blinks alone left a borderline score
        // borderline, and roughly one genuine resident in five ended inconclusive.
        for (int i = 0; i < 30; i++) {
            Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
            rig.run(400);
            assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
            assertTrue(rig.prompts.stream().anyMatch(p -> p.contains("turn") || p.contains("smile")), rig.describe());
        }
    }

    @Test
    void anotherFaceInTheSignedCaptureIsRejectedByTheIdentityLock() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.captureFace = SOMEONE_ELSE;
        rig.run(400);
        assertEquals(FailureReason.REJECT_BINDING, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertEquals(List.of("identity:IDENTITY_MISMATCH"), rig.session.bindingFailures());
        assertTrue(rig.engine.acceptedCapture().isEmpty());
    }

    @Test
    void aSecondFaceStillInViewWhenTheDecisionIsMadeIsAVeto() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.resident.secondFaceFromFrame = 10;
        rig.run(400);
        assertEquals(FailureReason.REJECT_VETO, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertTrue(rig.guidance.contains(Guidance.ONLY_ONE_PERSON));
    }

    @Test
    void aNewTrackRestartsTheEvidenceAndTheCaptureMustMatchTheNewTrack() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.resident.newTrackAtFrame = 8;
        rig.run(400);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.describe());
        assertEquals(2, rig.session.evidenceTrackId(), "the evidence belongs to the face that finished");
        assertTrue(rig.session.history().stream().anyMatch(t -> t.why().contains("face lost")), rig.describe());
    }

    @Test
    void anExceptionInPerceptionFailsTheAttemptClosed() {
        Rig rig = new Rig(10, EnumSet.noneOf(Capability.class));
        rig.resident.passive = 0.97;
        rig.resident.moire = 0.90;
        rig.resident.throwAtFrame = 6;
        rig.run(400);
        assertEquals(FailureReason.ENGINE_ERROR, rig.session.lastFailure().orElseThrow(), rig.describe());
        assertFalse(rig.states.contains(SessionState.CAPTURING), "an engine error never falls through to capture");
    }

    @Test
    void challengesNobodyCanVerifyAreNeverDrawn() {
        // The policy pool has GAZE and FLASH and this device has a resident-facing display, but no verifier for
        // either exists yet. A step nobody checks could only ever time out, costing the resident an attempt.
        for (int i = 0; i < 25; i++) {
            Rig rig = new Rig(30, EnumSet.of(Capability.RESIDENT_DISPLAY));
            rig.resident.behaviour = Behaviour.STILL;
            rig.runUntil(400, SessionState.CHALLENGING);
            ChallengePlan plan = rig.engine.challengePlan().orElseThrow();
            assertTrue(plan.flash().isEmpty(), "flash drawn");
            assertTrue(plan.steps().stream().noneMatch(c -> c.kind() == ChallengeKind.GAZE), "gaze drawn: " + plan.steps());
        }
    }

    // ------------------------------------------------------------------------------------------ the rig


    // Package-private (not private) so EvidencePackagerTest can drive the same real engine to ACCEPTED.
    static final class Rig {
        final TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        final MockFrameSource source;
        final LivenessSession session;
        final LivenessEngine engine;
        final SimulatedResident resident = new SimulatedResident(clock);
        final List<SessionState> states = new ArrayList<>();
        final List<Guidance> guidance = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        final List<String> audits = new ArrayList<>();
        float[] captureFace = RESIDENT;

        Rig(double fps, Set<Capability> capabilities) {
            this(bundle, fps, capabilities);
        }

        /** As above, on an explicitly given bundle: lets another test class drive the same real engine. */
        Rig(EngineBundle bundle, double fps, Set<Capability> capabilities) {
            this(bundle, fps, capabilities, null);
        }

        /**
         * As above, with the {@link DeviceCapabilities} the engine sees overridden (e.g. to test the injection
         * guard, #84, against a device the mock source itself would never report); null uses the mock's own.
         */
        Rig(EngineBundle bundle, double fps, Set<Capability> capabilities, DeviceCapabilities deviceOverride) {
            MockFrameSource.Builder b = MockFrameSource.builder(clock).delayer(clock::advance).fps(fps).neutral(List.of(JPEG));
            for (Capability c : capabilities) {
                b.capability(c);
            }
            try {
                source = b.build();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            DeviceCapabilities device = deviceOverride != null ? deviceOverride : source.discover();
            session = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", new SessionRegistry(),
                    e -> audits.add(e.type() + " " + e.fields()), clock, new SecureRandom());
            CaptureAnalyser captures = c -> captureFace;
            EngineListener listener = new EngineListener() {
                @Override
                public void stateChanged(SessionState state) {
                    states.add(state);
                }

                @Override
                public void guidance(Guidance g) {
                    guidance.add(g);
                }

                @Override
                public void prompt(ChallengePrompt prompt, int step, int steps) {
                    prompts.add(step + ":" + prompt.messageId());
                    resident.respond(prompt);
                }
            };
            engine = LivenessEngine.builder(bundle, session)
                    .device(source, source.trust(), DeviceLevel.L1)
                    .analysers(resident, captures)
                    .capabilities(SessionCapabilities.of(fps, device))
                    .random(new SecureRandom())
                    .listener(listener)
                    .requestedScore(40)
                    .build();
            source.start(null, engine);
            engine.start();
        }

        void run(int maxFrames) {
            for (int i = 0; i < maxFrames && !settled(); i++) {
                source.step();
            }
        }

        void runUntil(int maxFrames, SessionState target) {
            for (int i = 0; i < maxFrames && session.state() != target && !settled(); i++) {
                source.step();
            }
            assertEquals(target, session.state(), describe());
        }

        boolean settled() {
            SessionState s = session.state();
            return s.terminal() || s == SessionState.RETRY_WAIT || s == SessionState.LOCKED_OUT
                    || s == SessionState.EXCEPTION_FLOW || s == SessionState.FAILED;
        }

        String describe() {
            StringBuilder sb = new StringBuilder("state ").append(session.state()).append(", failure ")
                    .append(session.lastFailure().orElse(null)).append(", prompts ").append(prompts).append('\n');
            for (Transition t : session.history()) {
                sb.append("  ").append(t).append('\n');
            }
            return sb.toString();
        }
    }
}
