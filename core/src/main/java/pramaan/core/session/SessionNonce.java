/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The per-session nonce (Build Map #18): 128 random bits as 32 lower-case hex characters. It is sent as
 * the SBI {@code transactionId}, which allows only letters, digits and hyphens (4-50 characters) — so
 * base64url, which can contain '_', is not an option.
 */
public final class SessionNonce {

    public static final Pattern FORMAT = Pattern.compile("[0-9a-f]{32}");

    private final String value;

    private SessionNonce(String value) {
        this.value = value;
    }

    /** Generates a fresh nonce. Never derived from the clock; one per session, never reused. */
    public static SessionNonce generate(SecureRandom random) {
        byte[] bytes = new byte[16];
        Objects.requireNonNull(random, "random").nextBytes(bytes);
        char[] hex = new char[32];
        for (int i = 0; i < bytes.length; i++) {
            hex[2 * i] = Character.forDigit((bytes[i] >> 4) & 0xF, 16);
            hex[2 * i + 1] = Character.forDigit(bytes[i] & 0xF, 16);
        }
        return new SessionNonce(new String(hex));
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionNonce && ((SessionNonce) other).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
