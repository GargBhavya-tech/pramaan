/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core;

/** The three places liveness runs in the Registration Client (Bible Part 15). */
public enum Workflow {
    /** A resident's face capture during enrolment — the most inclusive profile. */
    RESIDENT,
    /** Operator biometric login — strict. */
    OPERATOR,
    /** Supervisor authentication and approvals — strictest. */
    SUPERVISOR
}
