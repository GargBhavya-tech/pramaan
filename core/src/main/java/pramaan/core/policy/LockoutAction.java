/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

/** What happens when a subject runs out of attempts (Bible Part 12). */
public enum LockoutAction {
    /** Residents: supervisor-approved, rate-limited, audited recovery path. */
    EXCEPTION_FLOW,
    /** Operators: lock for 15 minutes and alert an administrator. */
    LOCK_15_MIN_AND_ALERT,
    /** Supervisors: lock until an administrator intervenes. */
    LOCK_AND_ALERT
}
