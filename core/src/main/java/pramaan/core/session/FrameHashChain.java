/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Tamper-evident record of the frames a decision was made on (Bible Part 13, lock 2; Build Map #53):
 * {@code H0 = SHA256(nonce)}, {@code Hi = SHA256(Hi-1 || SHA256(frame_i) || t_i)}. Removing, reordering or
 * substituting any frame changes the head, and so does starting from a different nonce.
 *
 * <p>Conventions, fixed so a server can recompute it: the nonce is hashed as the 32 ASCII hex characters
 * sent as {@code transactionId}; {@code frame_i} is the exact bytes analysed (never re-encoded);
 * {@code t_i} is the frame's arrival time in epoch milliseconds, 8 bytes big-endian.
 */
public final class FrameHashChain {

    private final String nonce;
    private byte[] head;
    private long frames;
    private long lastMillis = Long.MIN_VALUE;

    public FrameHashChain(SessionNonce nonce) {
        this.nonce = Objects.requireNonNull(nonce, "nonce").value();
        this.head = sha256(this.nonce.getBytes(StandardCharsets.US_ASCII));
    }

    /** Folds one analysed frame in. Time may not run backwards within a chain. */
    public void append(byte[] frameBytes, long epochMillis) {
        Objects.requireNonNull(frameBytes, "frameBytes");
        if (epochMillis < lastMillis) {
            throw new IllegalArgumentException("frame at " + epochMillis + " ms is older than the previous one at " + lastMillis);
        }
        MessageDigest md = digest();
        md.update(head);
        md.update(sha256(frameBytes));
        md.update(ByteBuffer.allocate(8).putLong(epochMillis).array());
        head = md.digest();
        lastMillis = epochMillis;
        frames++;
    }

    /** Lowercase hex of the current head; equals SHA256(nonce) before any frame. */
    public String head() {
        StringBuilder s = new StringBuilder(64);
        for (byte b : head) {
            s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return s.toString();
    }

    public long frames() {
        return frames;
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
