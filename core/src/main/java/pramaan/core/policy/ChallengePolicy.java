/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/** Which challenges a profile may use and how many; timing limits for each step. */
public final class ChallengePolicy {

    private final List<ChallengeKind> pool;
    private final int minSteps;
    private final int maxSteps;
    private final Duration stepTimeout;
    private final Duration onsetMin;

    ChallengePolicy(List<ChallengeKind> pool, int minSteps, int maxSteps, Duration stepTimeout, Duration onsetMin) {
        this.pool = Collections.unmodifiableList(pool);
        this.minSteps = minSteps;
        this.maxSteps = maxSteps;
        this.stepTimeout = stepTimeout;
        this.onsetMin = onsetMin;
    }

    static ChallengePolicy read(StrictNode n) {
        List<ChallengeKind> pool = n.enumList("pool", ChallengeKind.class);
        int minSteps = n.integer("minSteps");
        int maxSteps = n.integer("maxSteps");
        int stepTimeoutMs = n.integer("stepTimeoutMs");
        int onsetMinMs = n.integer("onsetMinMs");
        n.finish();

        String p = n.path();
        if (pool.isEmpty()) {
            throw new InvalidPolicyException(p + ".pool: must not be empty");
        }
        if (minSteps < 1 || maxSteps < minSteps || maxSteps > 5) {
            throw new InvalidPolicyException(p + ": need 1 <= minSteps <= maxSteps <= 5, got " + minSteps + ".." + maxSteps);
        }
        if (stepTimeoutMs < 1_000) {
            throw new InvalidPolicyException(p + ".stepTimeoutMs: must be at least 1000, got " + stepTimeoutMs);
        }
        if (onsetMinMs < 0 || onsetMinMs >= stepTimeoutMs) {
            throw new InvalidPolicyException(p + ".onsetMinMs: must be in [0, stepTimeoutMs), got " + onsetMinMs);
        }
        return new ChallengePolicy(pool, minSteps, maxSteps, Duration.ofMillis(stepTimeoutMs), Duration.ofMillis(onsetMinMs));
    }

    public List<ChallengeKind> pool() {
        return pool;
    }

    public int minSteps() {
        return minSteps;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public Duration stepTimeout() {
        return stepTimeout;
    }

    /** Onsets earlier than this after a prompt are physiologically implausible (Bible Part 11.5). */
    public Duration onsetMin() {
        return onsetMin;
    }
}
