/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

/**
 * How strongly the liveness evidence is bound to the signed capture (Bible Part 41, "Integration in PRAMAAN"). The
 * server weights evidence accordingly.
 */
public enum BindingLevel {
    /** The four locks of Part 13 only: nonce, chain, identity and time. Sound, but the preview itself is unsigned. */
    HEURISTIC,
    /** The device also authenticated the preview stream and the capture carries the stream's tag head. */
    CRYPTOGRAPHIC
}
