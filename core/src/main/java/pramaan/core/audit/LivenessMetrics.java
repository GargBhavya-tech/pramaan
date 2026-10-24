/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import pramaan.core.Workflow;
import pramaan.core.session.AttemptRecord;

/**
 * Anonymised operational metrics for the liveness feature (PS04, good-to-have): average liveness processing time,
 * challenge completion time, retry rate and failure rate, per workflow.
 *
 * <p>It is fed the same {@link AuditEvent} stream the audit log gets, so nothing new is instrumented in the session.
 * Nothing it keeps or reports identifies a resident, operator, centre or device: the session id is used only to pair
 * a start with an end and is dropped when the attempt ends. Time is passed in with every event, never read from a
 * clock inside, so tests can simulate it.
 *
 * <p>Definitions (an <i>attempt</i> is one pass through the session, retries are further attempts):
 * <ul>
 *   <li>processing time: first transition out of {@code IDLE} to the attempt ending, in milliseconds;</li>
 *   <li>challenge completion time: entering {@code CHALLENGING} to leaving it, only for attempts that got one;</li>
 *   <li>retry rate: attempts numbered above 1 as a share of all attempts;</li>
 *   <li>failure rate: attempts that did not end {@code ACCEPTED} as a share of all attempts.</li>
 * </ul>
 * These are rates of attempts, not error rates of the detector: no ground truth is known here.
 */
public final class LivenessMetrics implements Consumer<AuditEvent> {

    private final LongSupplier nowMs;
    private final Map<String, Open> open = new HashMap<>();
    private final Map<Workflow, Totals> totals = new EnumMap<>(Workflow.class);

    /** @param nowMs the clock used by {@link #accept}; use {@link #record} to pass each timestamp explicitly */
    public LivenessMetrics(LongSupplier nowMs) {
        this.nowMs = nowMs;
    }

    @Override
    public synchronized void accept(AuditEvent event) {
        record(event, nowMs.getAsLong());
    }

    /** Folds one audit event into the metrics. Events that are not about an attempt's progress are ignored. */
    public synchronized void record(AuditEvent event, long atMs) {
        Object id = event.fields().get("sessionId");
        if (id == null) {
            return;
        }
        String session = String.valueOf(id);
        switch (event.type()) {
            case "SESSION_TRANSITION":
                transition(session, String.valueOf(event.fields().get("from")), String.valueOf(event.fields().get("to")),
                        atMs);
                break;
            case "ATTEMPT_ENDED":
                ended(session, event, atMs);
                break;
            default:
                break;
        }
    }

    private void transition(String session, String from, String to, long atMs) {
        Open o = open.computeIfAbsent(session, k -> new Open(atMs));
        if ("CHALLENGING".equals(to)) {
            o.challengeStartedAt = atMs;
        }
        if ("CHALLENGING".equals(from) && o.challengeStartedAt >= 0) {
            o.challengeMs = atMs - o.challengeStartedAt;
            o.challengeStartedAt = -1;
        }
    }

    private void ended(String session, AuditEvent event, long atMs) {
        Open o = open.remove(session);
        Workflow workflow = workflowOf(event.fields().get("workflow"));
        if (workflow == null) {
            return;
        }
        Totals t = totals.computeIfAbsent(workflow, k -> new Totals());
        t.attempts++;
        Object attempt = event.fields().get("attempt");
        if (attempt instanceof Number && ((Number) attempt).intValue() > 1) {
            t.retries++;
        }
        if (!AttemptRecord.ACCEPTED.equals(event.fields().get("outcome"))) {
            t.failures++;
        }
        if (o != null) {
            t.processingMsSum += Math.max(0, atMs - o.startedAt);
            t.processingCount++;
            if (o.challengeMs >= 0) {
                t.challengeMsSum += o.challengeMs;
                t.challengeCount++;
            }
        }
    }

    private static Workflow workflowOf(Object name) {
        if (name == null) {
            return null;
        }
        try {
            return Workflow.valueOf(String.valueOf(name));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A copy of the current numbers for one workflow; all zeros before anything has been recorded. */
    public synchronized Snapshot snapshot(Workflow workflow) {
        Totals t = totals.get(workflow);
        return t == null ? new Snapshot(workflow, 0, 0, 0, 0, 0, 0, 0)
                : new Snapshot(workflow, t.attempts, t.retries, t.failures, t.processingMsSum, t.processingCount,
                        t.challengeMsSum, t.challengeCount);
    }

    /** A plain-text table for the diagnostics window or a support ticket: counts and averages, no identifiers. */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder("workflow    attempts  retry%  fail%  avg-liveness-ms  avg-challenge-ms\n");
        for (Workflow w : Workflow.values()) {
            Snapshot s = snapshot(w);
            if (s.attempts() == 0) {
                continue;
            }
            sb.append(String.format("%-11s %8d  %6.1f  %5.1f  %15s  %16s%n", w, s.attempts(), 100 * s.retryRate(),
                    100 * s.failureRate(), fmt(s.averageProcessingMs()), fmt(s.averageChallengeMs())));
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.0f", v);
    }

    private static final class Open {
        final long startedAt;
        long challengeStartedAt = -1;
        long challengeMs = -1;

        Open(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private static final class Totals {
        long attempts;
        long retries;
        long failures;
        long processingMsSum;
        long processingCount;
        long challengeMsSum;
        long challengeCount;
    }

    /** Immutable numbers for one workflow. An average with nothing behind it is {@code NaN}, never zero. */
    public static final class Snapshot {
        private final Workflow workflow;
        private final long attempts;
        private final long retries;
        private final long failures;
        private final long processingMsSum;
        private final long processingCount;
        private final long challengeMsSum;
        private final long challengeCount;

        Snapshot(Workflow workflow, long attempts, long retries, long failures, long processingMsSum,
                long processingCount, long challengeMsSum, long challengeCount) {
            this.workflow = workflow;
            this.attempts = attempts;
            this.retries = retries;
            this.failures = failures;
            this.processingMsSum = processingMsSum;
            this.processingCount = processingCount;
            this.challengeMsSum = challengeMsSum;
            this.challengeCount = challengeCount;
        }

        public Workflow workflow() {
            return workflow;
        }

        public long attempts() {
            return attempts;
        }

        public double retryRate() {
            return attempts == 0 ? Double.NaN : (double) retries / attempts;
        }

        public double failureRate() {
            return attempts == 0 ? Double.NaN : (double) failures / attempts;
        }

        public double averageProcessingMs() {
            return processingCount == 0 ? Double.NaN : (double) processingMsSum / processingCount;
        }

        public double averageChallengeMs() {
            return challengeCount == 0 ? Double.NaN : (double) challengeMsSum / challengeCount;
        }

        public long challengedAttempts() {
            return challengeCount;
        }
    }
}
