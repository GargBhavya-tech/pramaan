/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Uniform integers derived from a secret seed: block i = HMAC-SHA256(seed, "PRAMAAN-challenge-v1" || i).
 * Anyone holding the seed (the server, after the session) can regenerate exactly the same sequence; nobody
 * without it can predict it. Uniformity uses rejection sampling, never a biased modulo.
 */
final class DeterministicStream {

    private static final byte[] LABEL = "PRAMAAN-challenge-v1".getBytes(StandardCharsets.US_ASCII);

    private final Mac mac;
    private long counter;
    private byte[] block = new byte[0];
    private int pos;

    DeterministicStream(byte[] seed) {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed, "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java runtime", e);
        }
    }

    private int nextByte() {
        if (pos == block.length) {
            mac.update(LABEL);
            mac.update(ByteBuffer.allocate(8).putLong(counter++).array());
            block = mac.doFinal();
            pos = 0;
        }
        return block[pos++] & 0xFF;
    }

    /** Uniform in [0, n). */
    int uniformInt(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        long range = 1L << 32;
        long limit = range - range % n;
        while (true) {
            long u = ((long) nextByte() << 24) | (nextByte() << 16) | (nextByte() << 8) | nextByte();
            if (u < limit) {
                return (int) (u % n);
            }
        }
    }
}
