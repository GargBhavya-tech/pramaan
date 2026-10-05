/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.crypto;

/** A JWS that must not be trusted: bad structure, unsupported algorithm, untrusted signer or bad signature. */
public final class JwsVerificationException extends Exception {

    private static final long serialVersionUID = 1L;

    public JwsVerificationException(String message) {
        super(message);
    }

    public JwsVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
