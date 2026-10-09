/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;

/** Build Map #50: dual control, fixed reasons, liveness on the exception capture, rolling daily limits. */
class ExceptionFlowTest {

    private SessionFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SessionFixtures();
    }

    /** A resident who reaches the exception flow without costing an attempt: an infant at a plain desk. */
    private LivenessSession inExceptionFlow(String subject, String operator) {
        LivenessSession s = fx.session(Workflow.RESIDENT, subject, operator);
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.infantPlan());
        assertEquals(SessionState.EXCEPTION_FLOW, s.state(), "no action path: supervised exception, never a reject");
        assertEquals(0, fx.registry.consumedAttempts(subject));
        return s;
    }

    @Test
    void anExceptionCannotCompleteWithoutAPassingSupervisorVerdict() {
        LivenessSession s = inExceptionFlow("R1", "OP1");
        assertEquals(ExceptionOutcome.REJECTED_NO_SUPERVISOR,
                s.requestException(ExceptionReason.INFANT_OR_YOUNG_CHILD, null, fx.accept(Workflow.RESIDENT)));
        assertEquals(SessionState.EXCEPTION_FLOW, s.state(), "the operator can still fetch a supervisor");
        SupervisorVerdict v = fx.supervisorVerdict("SUP1", s.sessionId());
        assertEquals(ExceptionOutcome.APPROVED,
                s.requestException(ExceptionReason.INFANT_OR_YOUNG_CHILD, v, fx.accept(Workflow.RESIDENT)));
        assertEquals(SessionState.EXCEPTION_APPROVED, s.state());
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("EXCEPTION_REQUEST")
                && "SUP1".equals(e.fields().get("supervisorId")) && "INFANT_OR_YOUNG_CHILD".equals(e.fields().get("reason"))));
    }

    @Test
    void dualControlFreshnessAndOneVerdictPerResident() {
        LivenessSession r1 = inExceptionFlow("R1", "OP1");
        SupervisorVerdict self = fx.supervisorVerdict("OP1", r1.sessionId());
        assertEquals(ExceptionOutcome.REJECTED_SAME_PERSON,
                r1.requestException(ExceptionReason.MEDICAL_CONDITION, self, fx.accept(Workflow.RESIDENT)));

        LivenessSession r2 = inExceptionFlow("R2", "OP1");
        SupervisorVerdict old = fx.supervisorVerdict("SUP1", r2.sessionId());
        fx.clock.advance(Duration.ofMinutes(11));
        assertEquals(ExceptionOutcome.REJECTED_STALE_VERDICT,
                r2.requestException(ExceptionReason.MEDICAL_CONDITION, old, fx.accept(Workflow.RESIDENT)));

        LivenessSession r3 = inExceptionFlow("R3", "OP1");
        SupervisorVerdict once = fx.supervisorVerdict("SUP1", r3.sessionId());
        assertEquals(ExceptionOutcome.APPROVED,
                r3.requestException(ExceptionReason.MEDICAL_CONDITION, once, fx.accept(Workflow.RESIDENT)));
        // The same verdict for the next resident: it approves R3's session and nothing else (Build Map #77).
        assertEquals(ExceptionOutcome.REJECTED_WRONG_PURPOSE,
                inExceptionFlow("R4", "OP1").requestException(ExceptionReason.MEDICAL_CONDITION, once, fx.accept(Workflow.RESIDENT)));
        // A supervisor login, or an approval of a packet, is not an exception approval either.
        LivenessSession r5 = inExceptionFlow("R5", "OP1");
        assertEquals(ExceptionOutcome.REJECTED_WRONG_PURPOSE, r5.requestException(ExceptionReason.MEDICAL_CONDITION,
                fx.supervisorVerdict("SUP1", "login"), fx.accept(Workflow.RESIDENT)));
    }

    @Test
    void aPhotoOfAPhotoBlocksThePathForGood() {
        LivenessSession s = inExceptionFlow("R1", "OP1");
        assertEquals(ExceptionOutcome.BLOCKED_CAPTURE_ATTACK, s.requestException(ExceptionReason.FACIAL_DIFFERENCE,
                fx.supervisorVerdict("SUP1", s.sessionId()), fx.reject(Workflow.RESIDENT)));
        assertEquals(SessionState.EXCEPTION_BLOCKED, s.state());
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("EXCEPTION_CAPTURE_ATTACK")
                && "HIGH".equals(e.fields().get("severity"))));
        assertThrows(IllegalStateException.class, () -> s.requestException(ExceptionReason.FACIAL_DIFFERENCE,
                fx.supervisorVerdict("SUP2", s.sessionId()), fx.accept(Workflow.RESIDENT)));
    }

    @Test
    void exceedingTheDailyLimitBlocksFurtherExceptions() {
        int perOperator = fx.policy.exceptionMaxPerOperatorPerDay();
        for (int i = 0; i < perOperator; i++) {
            LivenessSession r = inExceptionFlow("R" + i, "OP1");
            assertEquals(ExceptionOutcome.APPROVED, r.requestException(
                    ExceptionReason.MOTOR_IMPAIRMENT, fx.supervisorVerdict("SUP1", r.sessionId()), fx.accept(Workflow.RESIDENT)));
            fx.clock.advance(Duration.ofMinutes(30));
        }
        LivenessSession over = inExceptionFlow("RX", "OP1");
        assertEquals(ExceptionOutcome.BLOCKED_DAILY_LIMIT, over.requestException(ExceptionReason.MOTOR_IMPAIRMENT,
                fx.supervisorVerdict("SUP1", over.sessionId()), fx.accept(Workflow.RESIDENT)));
        assertEquals(SessionState.EXCEPTION_BLOCKED, over.state());
        // Another operator at the same centre still has room; after 24 hours this operator does too.
        LivenessSession ry = inExceptionFlow("RY", "OP2");
        assertEquals(ExceptionOutcome.APPROVED, ry.requestException(
                ExceptionReason.MOTOR_IMPAIRMENT, fx.supervisorVerdict("SUP1", ry.sessionId()), fx.accept(Workflow.RESIDENT)));
        fx.clock.advance(Duration.ofHours(24));
        LivenessSession rz = inExceptionFlow("RZ", "OP1");
        assertEquals(ExceptionOutcome.APPROVED, rz.requestException(
                ExceptionReason.MOTOR_IMPAIRMENT, fx.supervisorVerdict("SUP1", rz.sessionId()), fx.accept(Workflow.RESIDENT)));
    }

    @Test
    void theExceptionFlowIsOnlyReachableFromLockoutOrANoActionPath() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        s.start();
        assertThrows(IllegalStateException.class, () -> s.requestException(ExceptionReason.MOTOR_IMPAIRMENT,
                fx.supervisorVerdict("SUP1", s.sessionId()), fx.accept(Workflow.RESIDENT)));
    }
}
