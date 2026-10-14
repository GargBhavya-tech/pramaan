/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The authenticated-preview tag chain of the SBI Liveness Profile (Bible Part 41, point 2; Build Map #100):
 *
 * <pre>tag_0 = HMAC_Ks(nonce)
 * tag_i = HMAC_Ks(tag_{i-1} || SHA-256(frame_i) || t_i)        (t_i as 8 big-endian bytes, milliseconds)</pre>
 *
 * <p>The device computes one tag per frame it sends; a client that shares the session key {@code Ks} computes the same
 * chain over the frames it received. They agree only if no frame was dropped, reordered, replaced or retimed, and the
 * head at the moment of capture is what the signed capture carries, so the signed capture is linked to the exact
 * frames the liveness engine analysed. An HMAC per frame costs microseconds; the full signature over the head is
 * needed only every N frames.
 *
 * <p>Both ends use this class. It is not thread-safe: one chain per stream.
 */
public final class StreamTagChain {

    public static final int TAG_BYTES = 32;
    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec key;
    private byte[] head;
    private long frames;

    /** Starts the chain at {@code tag_0 = HMAC_Ks(nonce)}. */
    public StreamTagChain(byte[] sessionKey, byte[] nonce) {
        if (Objects.requireNonNull(sessionKey, "sessionKey").length < 16) {
            throw new IllegalArgumentException("a session key shorter than 128 bits is not a key");
        }
        this.key = new SecretKeySpec(sessionKey, HMAC);
        this.head = mac(Objects.requireNonNull(nonce, "nonce"));
    }

    /** Advances the chain over one frame and returns its tag. */
    public byte[] next(byte[] frame, long timestampMillis) {
        byte[] digest = sha256(Objects.requireNonNull(frame, "frame"));
        ByteBuffer in = ByteBuffer.allocate(head.length + digest.length + Long.BYTES);
        in.put(head).put(digest).putLong(timestampMillis);
        head = mac(in.array());
        frames++;
        return head.clone();
    }

    /** The tag of the latest frame, or {@code tag_0} before any. */
    public byte[] head() {
        return head.clone();
    }

    /** How many frames the chain has covered. */
    public long frames() {
        return frames;
    }

    /** Compares two tags without leaking where they differ. */
    public static boolean sameTag(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    private byte[] mac(byte[] data) {
        try {
            Mac m = Mac.getInstance(HMAC);
            m.init(key);
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
