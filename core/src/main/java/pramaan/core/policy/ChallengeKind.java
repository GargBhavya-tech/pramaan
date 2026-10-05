/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

/** Challenge types the generator may draw (Bible Part 10; Build Map #41). */
public enum ChallengeKind {
    /** Turn the head to a commanded side and angle. */
    TURN,
    /** A natural blink; needs roughly 10 fps or more (Build Map #14). */
    BLINK,
    /** Close the eyes for about a second; the fps-robust replacement for BLINK. */
    EYES_CLOSED,
    SMILE,
    /** Look at a point on a resident-facing screen. */
    GAZE,
    /** Screen-light colour sequence; needs a resident-facing light (Bible Part 8.6). */
    FLASH
}
