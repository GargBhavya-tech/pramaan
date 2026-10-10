/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

/** The evidence could not be trusted: bad signature, non-canonical payload, wrong schema. */
public final class EvidenceException extends Exception {

    private static final long serialVersionUID = 1L;

    public EvidenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
