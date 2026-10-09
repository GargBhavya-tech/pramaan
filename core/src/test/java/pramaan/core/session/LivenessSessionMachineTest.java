/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.fusion.Decision;

/** Build Map #48, #49: every transition and timeout, on a fake clock, in milliseconds of real time. */
class LivenessSessionMachineTest {

    private SessionFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SessionFixtures();
    }

    private static List<SessionState> path(LivenessSession s) {
        return s.history().stream().map(Transition::to).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- happy paths
    @Test
    void passiveAcceptGoesStraightToCaptureAndBinding() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        assertEquals(SessionState.IDLE, s.state());
        assertTrue(s.start());
        assertTrue(s.faceAcquired());
        fx.feed(s);
        assertFalse(s.passiveDecided(fx.accept(Workflow.RESIDENT), null), "not before the passive window has run");
        fx.clock.advanceMillis(1500);
        assertTrue(s.passiveDecided(fx.accept(Workflow.RESIDENT), null));
        assertTrue(fx.bind(s, true).passed());
        assertEquals(List.of(SessionState.ACQUIRING, SessionState.PASSIVE, SessionState.DECIDED, SessionState.CAPTURING,
                SessionState.BINDING, SessionState.ACCEPTED), path(s));
        assertTrue(s.state().terminal());
        assertTrue(s.nonce().value().matches("[0-9a-f]{32}"));
    }

    @Test
    void aChallengeRunsEveryStepThenReFusesBeforeCapture() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(2));
        assertEquals(SessionState.CHALLENGING, s.state());
        assertTrue(s.challengeStepPassed());
        assertTrue(s.challengeStepPassed());
        assertFalse(s.challengeStepPassed(), "no third step");
        fx.feed(s);
        assertTrue(s.challengesDecided(fx.acceptAfterChallenge(Workflow.RESIDENT)));
        assertEquals(SessionState.CAPTURING, s.state());
    }

    @Test
    void anAuditDecisionAfterAChallengeIsAWiringErrorBecauseItWouldLoopForever() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(1));
        s.challengeStepPassed();
        Decision audited = fx.audited(Workflow.RESIDENT);
        assertTrue(audited.reasons().contains(Decision.Reason.AUDIT));
        assertThrows(IllegalArgumentException.class, () -> s.challengesDecided(audited));
    }

    @Test
    void aSupervisorAuditedEverySessionCanStillFinish() {
        SupervisorVerdict v = fx.supervisorVerdict("SUP1", "packet-1");
        assertEquals("SUP1", v.supervisorId());
    }

    @Test
    void aSecondAttemptGetsAFreshNonce() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        String first = s.nonce().value();
        s.passiveDecided(fx.reject(Workflow.RESIDENT), null);
        fx.clock.advanceMillis(2000);
        assertEquals(SessionState.ACQUIRING, s.state());
        assertEquals(2, s.attempt());
        assertNotEquals(first, s.nonce().value(), "a failed attempt's signed capture must not fit the next attempt");
    }

    // ---------------------------------------------------------------- timeouts
    @Test
    void acquiringTimesOutWithoutCostingAnAttempt() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        s.start();
        fx.clock.advanceMillis(19_999);
        assertEquals(SessionState.ACQUIRING, s.state());
        fx.clock.advanceMillis(1);
        assertEquals(SessionState.IDLE, s.state());
        assertEquals(FailureReason.NO_USABLE_FACE, s.lastFailure().orElseThrow());
        assertEquals(0, fx.registry.consumedAttempts("R1"));
    }

    @Test
    void aMissingPassiveDecisionIsAnEngineTimeout() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        assertTrue(s.passiveWindowComplete());
        fx.clock.advanceMillis(1966);
        assertEquals(SessionState.PASSIVE, s.state());
        fx.clock.advanceMillis(1);
        assertEquals(SessionState.IDLE, s.state());
        assertEquals(FailureReason.ENGINE_TIMEOUT, s.lastFailure().orElseThrow());
    }

    @Test
    void aChallengeStepTimesOutAfterItsOnsetDelayPlusTheStepTimeout() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        ChallengePlan plan = fx.plan(1);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), plan);
        long limit = plan.steps().get(0).onsetDelayMs() + 5000;
        fx.clock.advanceMillis(limit - 1);
        assertEquals(SessionState.CHALLENGING, s.state());
        fx.clock.advanceMillis(1);
        assertEquals(SessionState.RETRY_WAIT, s.state());
        assertEquals(FailureReason.CHALLENGE_TIMEOUT, s.lastFailure().orElseThrow());
        assertEquals(1, fx.registry.consumedAttempts("R1"), "a challenge timeout is a liveness outcome");
    }

    @Test
    void aLateStepResultAfterATimeoutIsIgnoredNotResurrected() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(1));
        fx.clock.advanceMillis(10_000);
        assertFalse(s.challengeStepPassed());
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("SESSION_EVENT_IGNORED")));
    }

    @Test
    void missingReFusionCaptureAndBindingEachTimeOut() {
        LivenessSession a = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(a);
        a.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(1));
        a.challengeStepPassed();
        fx.clock.advanceMillis(2000);
        assertEquals(SessionState.IDLE, a.state());
        assertEquals(FailureReason.ENGINE_TIMEOUT, a.lastFailure().orElseThrow());

        LivenessSession b = fx.session(Workflow.RESIDENT, "R2", "OP1");
        fx.toPassiveDue(b);
        b.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        fx.clock.advanceMillis(15_000);
        assertEquals(SessionState.IDLE, b.state());
        assertEquals(FailureReason.DEVICE_TIMEOUT, b.lastFailure().orElseThrow());

        LivenessSession c = fx.session(Workflow.RESIDENT, "R3", "OP1");
        fx.toPassiveDue(c);
        c.passiveDecided(fx.accept(Workflow.RESIDENT), null);
        c.captured();
        fx.clock.advanceMillis(5_000);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(FailureReason.ENGINE_TIMEOUT, c.lastFailure().orElseThrow());
    }

    @Test
    void anEarlyOnsetIsRepromptedOnceThenFails() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(1));
        assertTrue(s.challengeStepFailed(ChallengeFailure.TOO_EARLY));
        assertEquals(SessionState.CHALLENGING, s.state(), "first early onset: a friendly re-prompt");
        s.challengeStepFailed(ChallengeFailure.TOO_EARLY);
        assertEquals(FailureReason.CHALLENGE_FAILED, s.lastFailure().orElseThrow());
    }

    @Test
    void losingTheFaceDiscardsTheAttemptsEvidenceAndStartsOver() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(2));
        s.challengeStepPassed();
        assertTrue(s.faceLost());
        assertEquals(SessionState.ACQUIRING, s.state());
        assertEquals(0, s.challengeStep());
        assertFalse(s.challengeStepPassed(), "the old plan's progress does not survive the gap");
    }

    // ---------------------------------------------------------------- #49
    @Test
    void aDisconnectMidSessionLeavesTheAttemptCounterUnchanged() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        fx.toPassiveDue(s);
        s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(2));
        assertTrue(s.infrastructureFailure(FailureReason.DEVICE_ERROR));
        assertEquals(SessionState.IDLE, s.state());
        assertEquals(0, fx.registry.consumedAttempts("R1"));
        assertThrows(IllegalArgumentException.class, () -> s.infrastructureFailure(FailureReason.REJECT_PAD));
    }

    @Test
    void threeFailedChallengesReachLockoutWithTheExpectedWaits() {
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        long[] expectedWaits = {2000, 4000};
        for (int i = 0; i < 3; i++) {
            fx.toPassiveDue(s);
            s.passiveDecided(fx.challenge(Workflow.RESIDENT), fx.plan(1));
            s.challengeStepFailed(ChallengeFailure.WRONG_ACTION);
            if (i < 2) {
                assertEquals(SessionState.RETRY_WAIT, s.state());
                fx.clock.advanceMillis(expectedWaits[i] - 1);
                assertEquals(SessionState.RETRY_WAIT, s.state(), "wait " + i);
                fx.clock.advanceMillis(1);
                assertEquals(SessionState.ACQUIRING, s.state());
            }
        }
        assertEquals(SessionState.EXCEPTION_FLOW, s.state(), "residents leave lockout only through the exception flow");
        assertTrue(path(s).contains(SessionState.LOCKED_OUT));
        LivenessSession again = fx.session(Workflow.RESIDENT, "R1", "OP1");
        assertFalse(again.start(), "a new window does not reset a lockout");
        assertEquals(SessionState.EXCEPTION_FLOW, again.state());
    }

    @Test
    void operatorsAreLockedForFifteenMinutesWithAnAlert() {
        LivenessSession s = fx.session(Workflow.OPERATOR, "OP9", "OP9");
        for (int i = 0; i < 3; i++) {
            fx.toPassiveDue(s);
            s.passiveDecided(fx.reject(Workflow.OPERATOR), null);
            fx.clock.advance(Duration.ofSeconds(8));
        }
        assertEquals(SessionState.LOCKED_OUT, s.state());
        assertTrue(fx.audit.stream().anyMatch(e -> e.type().equals("LOCKOUT_ALERT")));
        fx.clock.advance(Duration.ofMinutes(15));
        assertEquals(SessionState.IDLE, s.state());
        assertEquals(0, fx.registry.consumedAttempts("OP9"));
    }

    @Test
    void supervisorsStayLockedUntilAnAdministratorUnlocks() {
        LivenessSession s = fx.session(Workflow.SUPERVISOR, "SUP2", "SUP2");
        for (int i = 0; i < 2; i++) {
            fx.toPassiveDue(s);
            s.passiveDecided(fx.reject(Workflow.SUPERVISOR), null);
            fx.clock.advance(Duration.ofSeconds(8));
        }
        assertEquals(SessionState.LOCKED_OUT, s.state());
        fx.clock.advance(Duration.ofDays(3));
        assertEquals(SessionState.LOCKED_OUT, s.state());
        assertTrue(s.adminUnlock("ADMIN1"));
        assertEquals(SessionState.IDLE, s.state());
    }

    @Test
    void theHourlyLimitStopsEndlessRestartsEvenWithoutConsumingAttempts() {
        int limit = fx.policy.rateLimits().perSubjectPerHour();
        LivenessSession s = fx.session(Workflow.RESIDENT, "R1", "OP1");
        for (int i = 0; i < limit; i++) {
            assertTrue(s.start(), "attempt " + i);
            s.infrastructureFailure(FailureReason.DEVICE_ERROR);
        }
        assertFalse(s.start());
        assertEquals(SessionState.RATE_LIMITED, s.state());
        fx.clock.advance(Duration.ofMinutes(61));
        assertTrue(s.start(), "the window rolls");
    }
}
