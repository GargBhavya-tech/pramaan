/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;

class LivenessMetricsTest {

    private static AuditEvent transition(String session, String from, String to) {
        return AuditEvent.of("SESSION_TRANSITION").put("sessionId", session).put("from", from).put("to", to)
                .put("why", "test").put("attempt", 1).build();
    }

    private static AuditEvent ended(String session, Workflow w, int attempt, String outcome) {
        return AuditEvent.of("ATTEMPT_ENDED").put("sessionId", session).put("workflow", w.name()).put("attempt", attempt)
                .put("outcome", outcome).build();
    }

    @Test
    void nothingRecordedGivesNaNAveragesNotZero() {
        LivenessMetrics m = new LivenessMetrics(() -> 0);
        LivenessMetrics.Snapshot s = m.snapshot(Workflow.RESIDENT);
        assertEquals(0, s.attempts());
        assertTrue(Double.isNaN(s.averageProcessingMs()));
        assertTrue(Double.isNaN(s.failureRate()));
        assertEquals("workflow    attempts  retry%  fail%  avg-liveness-ms  avg-challenge-ms\n", m.report());
    }

    @Test
    void measuresProcessingAndChallengeTimeFromTransitions() {
        LivenessMetrics m = new LivenessMetrics(() -> 0);
        m.record(transition("a", "IDLE", "ACQUIRING"), 1_000);
        m.record(transition("a", "PASSIVE", "CHALLENGING"), 2_000);
        m.record(transition("a", "CHALLENGING", "CAPTURING"), 3_500);
        m.record(ended("a", Workflow.RESIDENT, 1, "ACCEPTED"), 4_000);
        LivenessMetrics.Snapshot s = m.snapshot(Workflow.RESIDENT);
        assertEquals(3_000.0, s.averageProcessingMs(), 0.0001);
        assertEquals(1_500.0, s.averageChallengeMs(), 0.0001);
        assertEquals(1, s.challengedAttempts());
        assertEquals(0.0, s.failureRate(), 0.0001);
        assertEquals(0.0, s.retryRate(), 0.0001);
    }

    @Test
    void countsRetriesAndFailuresPerWorkflowSeparately() {
        LivenessMetrics m = new LivenessMetrics(() -> 0);
        m.record(transition("a", "IDLE", "ACQUIRING"), 0);
        m.record(ended("a", Workflow.RESIDENT, 1, "LIVENESS_BELOW_THRESHOLD"), 100);
        m.record(transition("b", "IDLE", "ACQUIRING"), 200);
        m.record(ended("b", Workflow.RESIDENT, 2, "ACCEPTED"), 300);
        m.record(transition("c", "IDLE", "ACQUIRING"), 0);
        m.record(ended("c", Workflow.OPERATOR, 1, "ACCEPTED"), 50);
        LivenessMetrics.Snapshot r = m.snapshot(Workflow.RESIDENT);
        assertEquals(2, r.attempts());
        assertEquals(0.5, r.failureRate(), 0.0001);
        assertEquals(0.5, r.retryRate(), 0.0001);
        LivenessMetrics.Snapshot o = m.snapshot(Workflow.OPERATOR);
        assertEquals(1, o.attempts());
        assertEquals(0.0, o.failureRate(), 0.0001);
        assertTrue(Double.isNaN(o.averageChallengeMs()), "no challenge was shown, so there is no average, not zero");
    }

    @Test
    void anAttemptThatEndsWithoutAnyTransitionStillCountsButHasNoTiming() {
        LivenessMetrics m = new LivenessMetrics(() -> 0);
        m.record(ended("x", Workflow.SUPERVISOR, 1, "ENGINE_ERROR"), 10);
        LivenessMetrics.Snapshot s = m.snapshot(Workflow.SUPERVISOR);
        assertEquals(1, s.attempts());
        assertEquals(1.0, s.failureRate(), 0.0001);
        assertTrue(Double.isNaN(s.averageProcessingMs()));
    }

    @Test
    void usesTheInjectedClockWhenUsedAsAnAuditConsumerAndForgetsSessionIds() {
        AtomicLong clock = new AtomicLong(5_000);
        LivenessMetrics m = new LivenessMetrics(clock::get);
        m.accept(transition("secret-session-id", "IDLE", "ACQUIRING"));
        clock.set(7_500);
        m.accept(ended("secret-session-id", Workflow.RESIDENT, 1, "ACCEPTED"));
        assertEquals(2_500.0, m.snapshot(Workflow.RESIDENT).averageProcessingMs(), 0.0001);
        assertFalse(m.report().contains("secret-session-id"), "the report must carry no identifier");
        assertTrue(m.report().contains("RESIDENT"));
    }

    @Test
    void eventsWithoutASessionOrAnUnknownWorkflowAreIgnored() {
        LivenessMetrics m = new LivenessMetrics(() -> 0);
        m.record(AuditEvent.of("ATTEMPT_ENDED").put("workflow", "RESIDENT").put("outcome", "ACCEPTED").build(), 1);
        m.record(AuditEvent.of("ATTEMPT_ENDED").put("sessionId", "s").put("workflow", "NOT_A_WORKFLOW")
                .put("outcome", "ACCEPTED").build(), 1);
        for (Workflow w : Workflow.values()) {
            assertEquals(0, m.snapshot(w).attempts());
        }
    }
}
