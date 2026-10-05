/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.time.Duration;

/** Attempts and exponential backoff (Bible Part 12): wait = base * 2^(k-1) after the k-th failure. */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration backoffBase;

    RetryPolicy(int maxAttempts, Duration backoffBase) {
        this.maxAttempts = maxAttempts;
        this.backoffBase = backoffBase;
    }

    static RetryPolicy read(StrictNode n) {
        int max = n.integer("max");
        int backoffBaseMs = n.integer("backoffBaseMs");
        n.finish();
        if (max < 1 || max > 10) {
            throw new InvalidPolicyException(n.path() + ".max: must be 1..10, got " + max);
        }
        if (backoffBaseMs < 0) {
            throw new InvalidPolicyException(n.path() + ".backoffBaseMs: must not be negative, got " + backoffBaseMs);
        }
        return new RetryPolicy(max, Duration.ofMillis(backoffBaseMs));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration backoffBase() {
        return backoffBase;
    }
}
