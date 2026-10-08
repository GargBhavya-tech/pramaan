/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

/** Where a challenge step stands. Timeouts are judged by {@link LatencyRule}, which owns the clock. */
public enum StepStatus {
    IN_PROGRESS,
    PASS,
    /** A deliberate, different action — turning the wrong way, or turning hard when not asked. */
    WRONG_ACTION
}
