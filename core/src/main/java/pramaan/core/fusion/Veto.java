/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

/** Findings that bypass fusion and reject outright (Bible Part 9: hard vetoes). */
public enum Veto {
    /** A virtual camera or injected stream (Part 6). */
    INJECTION,
    /** The signed capture could not be tied to the observed session (Part 13). */
    BINDING_FAILURE,
    /** More than one face competing for the capture (Part 5). */
    MULTIPLE_FACES
}
