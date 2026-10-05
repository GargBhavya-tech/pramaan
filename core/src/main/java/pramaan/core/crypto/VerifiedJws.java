/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.crypto;

import java.nio.charset.StandardCharsets;

/** A JWS whose signature has been checked against a trusted signer. Only now may its payload be believed. */
public final class VerifiedJws {

    private final UnverifiedJws jws;
    private final String signer;

    VerifiedJws(UnverifiedJws jws, String signer) {
        this.jws = jws;
        this.signer = signer;
    }

    public String compact() {
        return jws.compact();
    }

    public String payloadText() {
        return jws.payloadText();
    }

    public byte[] payloadBytes() {
        return jws.payloadText().getBytes(StandardCharsets.UTF_8);
    }

    /** The certificate subject or pinned key id that signed it. */
    public String signer() {
        return signer;
    }
}
