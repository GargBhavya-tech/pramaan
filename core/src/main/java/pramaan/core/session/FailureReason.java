/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

/**
 * Why an attempt failed, and whether it costs the subject an attempt (Build Map #49). Only liveness and
 * PAD outcomes do: a broken cable, a busy device or an engine fault must never push a real person towards
 * lockout. Non-consuming failures still count against the hourly rate limits.
 */
public enum FailureReason {
    NO_USABLE_FACE(false),
    REJECT_PAD(true),
    /** A hard veto (injection, binding, multiple faces) rejected without fusion. */
    REJECT_VETO(true),
    CHALLENGE_FAILED(true),
    CHALLENGE_TIMEOUT(true),
    /** Challenges passed but fused evidence still did not reach accept. */
    INCONCLUSIVE(true),
    REJECT_BINDING(true),
    DEVICE_ERROR(false),
    DEVICE_TIMEOUT(false),
    ENGINE_ERROR(false),
    ENGINE_TIMEOUT(false),
    /** Nothing the resident can be asked to do, and not a resident: a configuration problem, not the person's. */
    NO_CHALLENGE_AVAILABLE(false),
    /** The injected clock ran backwards: timing cannot be trusted, so the session fails closed. */
    CLOCK_ANOMALY(false);

    private final boolean consumesAttempt;

    FailureReason(boolean consumesAttempt) {
        this.consumesAttempt = consumesAttempt;
    }

    public boolean consumesAttempt() {
        return consumesAttempt;
    }
}
