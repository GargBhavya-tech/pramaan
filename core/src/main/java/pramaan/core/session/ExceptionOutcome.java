/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

/** The result of asking for an exception. Only APPROVED and the two BLOCKED outcomes change state. */
public enum ExceptionOutcome {
    APPROVED,
    REJECTED_NO_SUPERVISOR,
    /** Dual control: the supervisor may not be the operator. */
    REJECTED_SAME_PERSON,
    /** The verdict approves something else: another resident, a packet, or nothing (a login). */
    REJECTED_WRONG_PURPOSE,
    REJECTED_STALE_VERDICT,
    REJECTED_VERDICT_REUSED,
    /** The exception capture itself looked like an attack (a photo of a photo, Bible Part 12). Final. */
    BLOCKED_CAPTURE_ATTACK,
    /** The operator's or centre's rolling-24-hour limit is spent; further exceptions wait for server review. Final. */
    BLOCKED_DAILY_LIMIT
}
