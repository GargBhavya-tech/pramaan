/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.crypto.VerifiedJws;

/**
 * The fifth binding lock, available only when the device implements the SBI Liveness Profile (Bible Part 41; Build
 * Map #100): the preview stream the liveness engine analysed was authenticated by the device, and the signed capture
 * names the head of that stream.
 *
 * <p>Usage: construct it with the agreed session key and the transaction nonce; {@link #observe} every frame as it
 * arrives with the tag the device sent; {@link #acceptCheckpoint} on each signed checkpoint the device emits; at
 * capture {@link #bindCapture} the {@code streamTagHead} found in the device-signed capture data, then
 * {@link #check}. The lock fails if any frame was dropped, reordered, replaced or retimed (the tag chain breaks at
 * that frame and stays broken), if the capture's head is not the stream's head, if the capture carries no head at all,
 * or if the stream has run too long without a signed checkpoint.
 *
 * <p>Not thread-safe: one lock per stream. Passing it makes the binding {@link BindingLevel#CRYPTOGRAPHIC}; a device
 * without the profile stays {@link BindingLevel#HEURISTIC}.
 */
public final class SignedStreamLock implements BindingLock {

    public static final String NAME = "signedStream";
    /** Frames the device may send between signed checkpoints before the stream stops being trusted (Part 41: N = 30). */
    public static final int DEFAULT_MAX_UNSIGNED_FRAMES = 60;

    private final StreamTagChain chain;
    private final int maxUnsignedFrames;
    private final List<byte[]> heads = new ArrayList<>();
    private int brokenAt = -1;
    private int lastCheckpoint = -1;
    private byte[] capturedHead;

    public SignedStreamLock(byte[] sessionKey, byte[] nonce) {
        this(sessionKey, nonce, DEFAULT_MAX_UNSIGNED_FRAMES);
    }

    public SignedStreamLock(byte[] sessionKey, byte[] nonce, int maxUnsignedFrames) {
        this.chain = new StreamTagChain(sessionKey, nonce);
        this.maxUnsignedFrames = maxUnsignedFrames;
        heads.add(chain.head()); // index 0 is tag_0
    }

    /**
     * Folds one received frame into the client's own chain and compares the result with the tag the device sent.
     *
     * @return false if the stream is, or just became, untrustworthy
     */
    public boolean observe(byte[] frame, long timestampMillis, byte[] deviceTag) {
        if (brokenAt >= 0) {
            return false;
        }
        byte[] expected = chain.next(frame, timestampMillis);
        heads.add(expected);
        if (!StreamTagChain.sameTag(expected, deviceTag)) {
            brokenAt = heads.size() - 1;
            return false;
        }
        return true;
    }

    /**
     * Accepts a device-signed checkpoint, {@code "<frame index>:<tag hex>"}, if it names a tag the client computed.
     *
     * @return false if the signature is untrusted or the tag differs from the client's own
     */
    public boolean acceptCheckpoint(String signedCheckpoint, JwsTrust trust) {
        VerifiedJws jws;
        try {
            jws = Jws.verify(signedCheckpoint, trust);
        } catch (JwsVerificationException e) {
            return false;
        }
        String text = new String(jws.payloadBytes(), StandardCharsets.UTF_8);
        int colon = text.indexOf(':');
        if (colon < 1) {
            return false;
        }
        int index;
        try {
            index = Integer.parseInt(text.substring(0, colon));
        } catch (NumberFormatException e) {
            return false;
        }
        if (index < 0 || index >= heads.size() || (brokenAt >= 0 && index >= brokenAt)) {
            return false;
        }
        if (!hex(heads.get(index)).equalsIgnoreCase(text.substring(colon + 1))) {
            return false;
        }
        lastCheckpoint = Math.max(lastCheckpoint, index);
        return true;
    }

    /** Records the {@code streamTagHead} read from the device-signed capture data; null when it carried none. */
    public void bindCapture(byte[] streamTagHead) {
        this.capturedHead = streamTagHead == null ? null : streamTagHead.clone();
    }

    /** The frame index at which the tag chain broke, or -1. */
    public int brokenAt() {
        return brokenAt;
    }

    public int framesObserved() {
        return heads.size() - 1;
    }

    @Override
    public LockOutcome check(BindingInput input, VerifiedCapture current, List<VerifiedCapture> earlier) {
        if (brokenAt >= 0) {
            return LockOutcome.fail(NAME, "STREAM_TAG_MISMATCH", brokenAt);
        }
        if (capturedHead == null) {
            return LockOutcome.fail(NAME, "STREAM_HEAD_MISSING", Double.NaN);
        }
        if (!StreamTagChain.sameTag(capturedHead, chain.head())) {
            return LockOutcome.fail(NAME, "STREAM_HEAD_MISMATCH", framesObserved());
        }
        if (framesObserved() - Math.max(lastCheckpoint, 0) > maxUnsignedFrames) {
            return LockOutcome.fail(NAME, "STREAM_UNSIGNED", framesObserved() - Math.max(lastCheckpoint, 0));
        }
        return LockOutcome.pass(NAME, framesObserved());
    }

    /** The level the binding reaches given whether the device speaks the profile and this lock passed. */
    public static BindingLevel level(boolean profileSupported, LockOutcome outcome) {
        return profileSupported && outcome != null && outcome.passed() ? BindingLevel.CRYPTOGRAPHIC
                : BindingLevel.HEURISTIC;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
