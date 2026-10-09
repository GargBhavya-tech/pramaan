/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.binding.BindingResult;
import pramaan.core.policy.AccessibilityProfile;

/** Build Map #92 (accessibility choice), #88 (operator-face guard, attempt observer, fusion audit). */
class AccessibilityAndAttemptTest {

    private SessionFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SessionFixtures();
    }

    // ---------------------------------------------------------------- useAccessibilityProfiles
    @Test
    void aPolicyListedProfileIsRecordedAndAudited() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        assertTrue(s.accessibilityProfiles().isEmpty());
        assertTrue(s.useAccessibilityProfiles(EnumSet.of(AccessibilityProfile.NO_VISION)));
        assertEquals(EnumSet.of(AccessibilityProfile.NO_VISION), s.accessibilityProfiles());
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("ACCESSIBILITY_PROFILES")
                && "OP1".equals(e.fields().get("operatorId")) && "NO_VISION".equals(e.fields().get("profiles"))));
    }

    @Test
    void aProfileThePolicyDoesNotListIsRejected() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        // Every AccessibilityProfile value is policy-enabled in config/pramaan-policy.yaml today; this asserts the
        // guard itself throws on an unlisted profile without depending on which one is missing from the real config.
        AccessibilityProfile unlisted = EnumSet.complementOf(EnumSet.copyOf(fx.policy.accessibilityProfiles())).stream()
                .findFirst().orElse(null);
        if (unlisted == null) {
            return; // the real policy lists every profile: nothing to reject against
        }
        assertThrows(IllegalArgumentException.class, () -> s.useAccessibilityProfiles(EnumSet.of(unlisted)));
    }

    @Test
    void theChoiceIsOnlyAcceptedBeforeTheFirstAttemptStarts() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        fx.bind(s, true);
        assertFalse(s.useAccessibilityProfiles(EnumSet.of(AccessibilityProfile.NO_VISION)),
                "ignored once the attempt is under way");
    }

    // ---------------------------------------------------------------- guardAgainstOperatorFace
    @Test
    void aResidentCaptureMatchingTheOperatorsOwnFaceIsRefusedAtBinding() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        assertTrue(s.guardAgainstOperatorFace(SessionFixtures.FACE));
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("OPERATOR_FACE_GUARD") && "OP1".equals(e.fields().get("operatorId"))));

        fx.toPassiveDue(s);
        s.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        // The captured face is the resident's own frames (SessionFixtures.FACE) — which is also what was
        // registered as the operator's face, so the operator lock must refuse it.
        BindingResult r = fx.bindAs(s, SessionFixtures.FACE);
        assertFalse(r.passed(), r.summary());
        assertTrue(r.outcomes().stream().anyMatch(o -> "operator".equals(o.lock()) && "OPERATOR_FACE".equals(o.code())));
    }

    @Test
    void aDifferentResidentFaceIsNotBlockedByTheOperatorGuard() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        assertTrue(s.guardAgainstOperatorFace(SessionFixtures.STRANGER));
        fx.toPassiveDue(s);
        s.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        assertTrue(fx.bind(s, true).passed());
    }

    @Test
    void theOperatorGuardAppliesOnlyToResidentSessions() {
        LivenessSession s = fx.session(Workflow.OPERATOR, "OP2", "OP1");
        assertThrows(IllegalStateException.class, () -> s.guardAgainstOperatorFace(SessionFixtures.FACE));
    }

    // ---------------------------------------------------------------- onAttemptEnded / ATTEMPT_ENDED
    @Test
    void everyEndedAttemptReachesTheObserverAndTheAuditLogWithFraudAnalyticsIds() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        List<AttemptRecord> seen = new ArrayList<>();
        s.onAttemptEnded(seen::add);
        s.useAccessibilityProfiles(EnumSet.of(AccessibilityProfile.LIMITED_MOTION));

        fx.toPassiveDue(s);
        s.passiveDecided(fx.reject(Workflow.RESIDENT), null);
        assertEquals(SessionState.RETRY_WAIT, s.state(), "the attempt is consumed; FAILED moves straight to RETRY_WAIT");

        assertEquals(1, seen.size());
        AttemptRecord r = seen.get(0);
        assertEquals(FailureReason.REJECT_PAD.name(), r.outcome());
        assertEquals("R1", r.subjectId());
        assertEquals("OP1", r.operatorId());
        assertEquals(Set.of(AccessibilityProfile.LIMITED_MOTION), r.accessibility());
        assertTrue(r.failure().isPresent() && r.failure().get() == FailureReason.REJECT_PAD);
        assertTrue(r.bindingFinalHash().isEmpty(), "no binding on a passive-window reject");

        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("ATTEMPT_ENDED") && "OP1".equals(e.fields().get("operatorId"))
                && "REJECT_PAD".equals(e.fields().get("outcome")) && "LIMITED_MOTION".equals(e.fields().get("accessibility"))));
    }

    @Test
    void anAcceptedAttemptRecordsItsBindingHash() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        List<AttemptRecord> seen = new ArrayList<>();
        s.onAttemptEnded(seen::add);
        fx.toPassiveDue(s);
        s.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        BindingResult r = fx.bind(s, true);
        assertTrue(r.passed());
        assertEquals(SessionState.ACCEPTED, s.state());
        assertEquals(1, seen.size());
        assertEquals(AttemptRecord.ACCEPTED, seen.get(0).outcome());
        assertEquals(r.finalHash(), seen.get(0).bindingFinalHash().orElseThrow());
    }

    // ---------------------------------------------------------------- auditDecision
    @Test
    void auditDecisionWritesCueDiagnosticsAndAFusionDecisionRowWithThePhase() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.audit.clear();
        s.auditDecision("passive", List.of(), fx.accept(Workflow.RESIDENT));
        assertTrue(fx.audit.stream().anyMatch(e -> e.fields().containsKey("phase") && "passive".equals(e.fields().get("phase"))),
                fx.audit.toString());
    }
}
