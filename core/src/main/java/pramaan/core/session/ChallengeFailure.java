/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

/** How a challenge step failed, as reported by the verifier (Bible Part 11). */
public enum ChallengeFailure {
    WRONG_ACTION,
    /** The action began before the prompt or implausibly soon after it. The first time: re-prompt, not fail. */
    TOO_EARLY,
    NOT_VERIFIED
}
