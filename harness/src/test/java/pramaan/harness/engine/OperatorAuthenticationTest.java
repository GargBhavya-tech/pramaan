/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.auth.FaceMatcher;
import pramaan.core.auth.OperatorAuthentication;
import pramaan.core.auth.OperatorAuthentication.Outcome;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;
import pramaan.harness.engine.SimulatedResident.Behaviour;
import pramaan.harness.mock.MockFrameSource;

/**
 * Build Map #76: operator login is strict liveness first, then the face match — never the other way round.
 * A photo of the operator fails and the matcher never runs; the live operator passes; the audit log shows both.
 */
class OperatorAuthenticationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    private static EngineBundle bundle;
    private final List<AuditEvent> audit = new ArrayList<>();
    private final SessionRegistry registry = new SessionRegistry();

    @BeforeAll
    static void loadBundle() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
    }

    /** Runs one operator liveness attempt to its end, with the simulated person in front of the camera. */
    private LivenessEngine login(String operatorId, double passive, double moire, Behaviour behaviour) throws Exception {
        TestClock clock = TestClock.at("2026-09-23T08:00:00Z");
        SimulatedResident person = new SimulatedResident(clock);
        person.passive = passive;
        person.moire = moire;
        person.behaviour = behaviour;
        MockFrameSource source = MockFrameSource.builder(clock).delayer(clock::advance).fps(10).neutral(List.of(JPEG)).build();
        LivenessSession session = bundle.newSession(Workflow.OPERATOR, operatorId, operatorId, "mock", "c1", registry,
                audit::add, clock, new SecureRandom());
        LivenessEngine engine = LivenessEngine.builder(bundle, session)
                .device(source, source.trust(), DeviceLevel.L1)
                .analysers(person, c -> SimulatedResident.RESIDENT)
                .capabilities(SessionCapabilities.of(10, source.discover()))
                .random(new SecureRandom())
                .listener(new EngineListener() {
                    @Override
                    public void prompt(ChallengePrompt p, int step, int steps) {
                        person.respond(p);
                    }
                })
                .requestedScore(40)
                .build();
        source.start(null, engine);
        engine.start();
        for (int i = 0; i < 400; i++) {
            SessionState s = session.state();
            if (s == SessionState.ACCEPTED || s == SessionState.RETRY_WAIT || s == SessionState.LOCKED_OUT) {
                break;
            }
            source.step();
        }
        return engine;
    }

    private static final class CountingMatcher implements FaceMatcher {
        final AtomicInteger calls = new AtomicInteger();
        final boolean same;

        CountingMatcher(boolean same) {
            this.same = same;
        }

        @Override
        public boolean matches(SignedCapture capture, String personId) {
            calls.incrementAndGet();
            return same;
        }
    }

    @Test
    void aPhotoOfTheOperatorFailsTheLiveOperatorPassesAndTheAuditLogShowsBoth() throws Exception {
        CountingMatcher matcher = new CountingMatcher(true);

        LivenessEngine photo = login("OP1", 0.10, 0.20, Behaviour.STILL);
        assertEquals(Outcome.LIVENESS_NOT_PASSED, OperatorAuthentication.authenticate("OP1", photo, matcher, audit::add));
        assertEquals(0, matcher.calls.get(), "the matcher never runs on a capture that failed liveness");

        LivenessEngine live = login("OP1", 0.97, 0.90, Behaviour.GENUINE);
        assertEquals(SessionState.ACCEPTED, live.session().state(), String.valueOf(live.session().history()));
        assertEquals(Outcome.AUTHENTICATED, OperatorAuthentication.authenticate("OP1", live, matcher, audit::add));
        assertEquals(1, matcher.calls.get());

        List<String> outcomes = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (e.type().equals("OPERATOR_AUTH")) {
                outcomes.add((String) e.fields().get("outcome"));
                assertEquals("OP1", e.fields().get("operatorId"));
            }
        }
        assertEquals(List.of("LIVENESS_NOT_PASSED", "AUTHENTICATED"), outcomes);
    }

    @Test
    void aLiveImpostorPassesLivenessButNotTheMatch() throws Exception {
        LivenessEngine live = login("OP2", 0.97, 0.90, Behaviour.GENUINE);
        assertEquals(Outcome.FACE_NOT_MATCHED, OperatorAuthentication.authenticate("OP2", live, new CountingMatcher(false),
                audit::add));
        assertTrue(audit.stream().anyMatch(e -> "FACE_NOT_MATCHED".equals(e.fields().get("outcome"))
                && "HIGH".equals(e.fields().get("severity"))));
    }

    @Test
    void aMatcherFailureIsARefusalNeverAPass() throws Exception {
        LivenessEngine live = login("OP3", 0.97, 0.90, Behaviour.GENUINE);
        FaceMatcher broken = (c, id) -> {
            throw new IllegalStateException("BioSDK unavailable");
        };
        assertEquals(Outcome.MATCH_UNAVAILABLE, OperatorAuthentication.authenticate("OP3", live, broken, audit::add));
    }

    @Test
    void onlyTheOperatorsOwnStrictSessionCanLogThemIn() throws Exception {
        LivenessEngine someoneElse = login("OP4", 0.97, 0.90, Behaviour.GENUINE);
        assertThrows(IllegalArgumentException.class,
                () -> OperatorAuthentication.authenticate("OP5", someoneElse, new CountingMatcher(true), audit::add));
        assertThrows(IllegalArgumentException.class, () -> bundle.newSession(Workflow.SUPERVISOR, "SUP1", "SUP1", "mock",
                "c1", registry, audit::add, TestClock.at("2026-09-23T08:00:00Z"), new SecureRandom()),
                "a supervisor session must name what it approves");
    }
}
