/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

/** Bible Part 12. DECIDED and FAILED are transient: they are recorded, then left at once. */
public enum SessionState {
    IDLE,
    ACQUIRING,
    PASSIVE,
    DECIDED,
    CHALLENGING,
    CAPTURING,
    BINDING,
    ACCEPTED,
    FAILED,
    RETRY_WAIT,
    LOCKED_OUT,
    /** Too many attempts this hour for this subject, operator or device. */
    RATE_LIMITED,
    EXCEPTION_FLOW,
    EXCEPTION_APPROVED,
    /** The exception path refused for good: an attack on the exception capture, or a daily limit reached. */
    EXCEPTION_BLOCKED,
    /** Failed closed (clock anomaly): nothing proceeds in this session. */
    UNAVAILABLE;

    public boolean terminal() {
        return this == ACCEPTED || this == EXCEPTION_APPROVED || this == EXCEPTION_BLOCKED || this == UNAVAILABLE;
    }

    boolean active() {
        return this == ACQUIRING || this == PASSIVE || this == CHALLENGING || this == CAPTURING || this == BINDING;
    }
}
