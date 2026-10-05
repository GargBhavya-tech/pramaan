/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * A JWS compact serialization ({@code header.payload.signature}) split and base64url-decoded,
 * <strong>without</strong> verifying the signature.
 *
 * <p>The name is deliberate. Nothing read from this class may be trusted: use {@link Jws#verify} before
 * any payload field feeds a decision (Build Map #54).
 */
public final class UnverifiedJws {

    private final String compact;
    private final byte[] header;
    private final byte[] payload;
    private final byte[] signature;

    private UnverifiedJws(String compact, byte[] header, byte[] payload, byte[] signature) {
        this.compact = compact;
        this.header = header;
        this.payload = payload;
        this.signature = signature;
    }

    /**
     * Splits and decodes a compact JWS.
     *
     * @throws IllegalArgumentException if it does not have exactly three base64url parts
     */
    public static UnverifiedJws parse(String compact) {
        Objects.requireNonNull(compact, "compact");
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("a compact JWS has 3 dot-separated parts, found " + parts.length);
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        try {
            return new UnverifiedJws(compact, decoder.decode(parts[0]), decoder.decode(parts[1]),
                    decoder.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("JWS part is not valid base64url", e);
        }
    }

    /** The original compact serialization, exactly as received. */
    public String compact() {
        return compact;
    }

    /** The protected header as UTF-8 JSON. */
    public String headerJson() {
        return new String(header, StandardCharsets.UTF_8);
    }

    /** The payload as UTF-8 text (JSON for SBI captures). Untrusted until verified. */
    public String payloadText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** The signing input, {@code base64url(header) + "." + base64url(payload)}, as the verifier needs it. */
    public String signingInput() {
        return compact.substring(0, compact.lastIndexOf('.'));
    }

    public byte[] signature() {
        return signature.clone();
    }
}
