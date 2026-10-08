/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.time.Duration;

/**
 * The human-timing check (Bible Part 11.5; Build Map #46): {@code latency = onset - prompt shown}.
 *
 * <p>Each bound gives the resident the benefit of the frame-rate doubt: "too early" is judged on the
 * <em>latest</em> the action could have started, "too late" on the <em>earliest</em>.
 * <ul>
 *   <li>Onset before the prompt was even shown: TOO_EARLY at any frame rate — nothing to resolve.</li>
 *   <li>Latency under 150 ms: TOO_EARLY (physiologically implausible: the clip already had the action in
 *       it). Inert below ~10 fps, where one frame is longer than the bound; the result says so.</li>
 *   <li>150 ms up to the policy's onsetMin (250 ms): passes, flagged FAST for fraud analytics.</li>
 *   <li>Past the step timeout: TIMEOUT.</li>
 * </ul>
 */
public final class LatencyRule {

    public static final long IMPLAUSIBLE_MS = 150;

    public enum Verdict {
        OK,
        /** Faster than typical but possible; passes, and is counted by fraud analytics. */
        FAST,
        TOO_EARLY,
        TIMEOUT
    }

    /** A verdict, plus whether the early-onset rule was switched off for this session's frame rate. */
    public static final class Result {
        private final Verdict verdict;
        private final boolean earlyRuleInert;
        private final long latencyMs;

        Result(Verdict verdict, boolean earlyRuleInert, long latencyMs) {
            this.verdict = verdict;
            this.earlyRuleInert = earlyRuleInert;
            this.latencyMs = latencyMs;
        }

        public Verdict verdict() {
            return verdict;
        }

        public boolean passed() {
            return verdict == Verdict.OK || verdict == Verdict.FAST;
        }

        public boolean earlyRuleInert() {
            return earlyRuleInert;
        }

        /** Latest onset minus prompt time. */
        public long latencyMs() {
            return latencyMs;
        }
    }

    private LatencyRule() {
    }

    public static Result judge(long promptShownMs, Onset onset, Duration onsetMin, Duration timeout,
            boolean earlyRuleEnabled) {
        long latency = onset.latestMs() - promptShownMs;
        boolean inert = !earlyRuleEnabled;
        if (onset.latestMs() < promptShownMs) {
            return new Result(Verdict.TOO_EARLY, inert, latency);
        }
        if (onset.earliestMs() - promptShownMs > timeout.toMillis()) {
            return new Result(Verdict.TIMEOUT, inert, latency);
        }
        if (earlyRuleEnabled) {
            if (latency < IMPLAUSIBLE_MS) {
                return new Result(Verdict.TOO_EARLY, false, latency);
            }
            if (latency < onsetMin.toMillis()) {
                return new Result(Verdict.FAST, false, latency);
            }
        }
        return new Result(Verdict.OK, inert, latency);
    }

    /** True once a step with no onset has run out of time. */
    public static boolean timedOut(long promptShownMs, long nowMs, Duration timeout) {
        return nowMs - promptShownMs > timeout.toMillis();
    }
}
