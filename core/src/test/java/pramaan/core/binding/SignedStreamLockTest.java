/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.testing.TestClock;

/** Bible Part 41 and Build Map #100: SignedStreamLockTest — drop, reorder, replace or retime a frame and the chain breaks. */
class SignedStreamLockTest {

    private static final byte[] KS = new byte[32];
    private static final byte[] NONCE = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String KID = "mock-device";

    private static KeyPair deviceKey;
    private static KeyPair strangerKey;
    private static JwsTrust trust;

    /** What the device put on the wire for one frame. */
    private static final class Sent {
        final byte[] frame;
        final long t;
        final byte[] tag;

        Sent(byte[] frame, long t, byte[] tag) {
            this.frame = frame;
            this.t = t;
            this.tag = tag;
        }
    }

    @BeforeEach
    void keys() throws Exception {
        Arrays.fill(KS, (byte) 7);
        if (deviceKey == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            deviceKey = g.generateKeyPair();
            strangerKey = g.generateKeyPair();
            trust = JwsTrust.builder(TestClock.at("2026-10-14T10:00:00Z")).key(KID, deviceKey.getPublic()).build();
        }
    }

    /** The device's stream: n frames, 100 ms apart, with a tag chain keyed by Ks and started from the nonce. */
    private static List<Sent> stream(int n, byte[] nonce) {
        StreamTagChain device = new StreamTagChain(KS, nonce);
        Random r = new Random(5);
        List<Sent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] frame = new byte[64];
            r.nextBytes(frame);
            long t = 1_000_000L + i * 100L;
            out.add(new Sent(frame, t, device.next(frame, t)));
        }
        return out;
    }

    private static boolean feed(SignedStreamLock lock, List<Sent> frames) {
        boolean ok = true;
        for (Sent s : frames) {
            ok &= lock.observe(s.frame, s.t, s.tag);
        }
        return ok;
    }

    private static String checkpoint(KeyPair signer, int index, byte[] head) throws Exception {
        return Jws.signRs256((index + ":" + hex(head)).getBytes(StandardCharsets.UTF_8), KID, signer.getPrivate());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static byte[] headAfter(List<Sent> frames, int count, byte[] nonce) {
        StreamTagChain c = new StreamTagChain(KS, nonce);
        for (int i = 0; i < count; i++) {
            c.next(frames.get(i).frame, frames.get(i).t);
        }
        return c.head();
    }

    @Test
    void anUntouchedStreamBindsTheCaptureCryptographically() throws Exception {
        List<Sent> frames = stream(40, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        assertTrue(feed(lock, frames));
        assertTrue(lock.acceptCheckpoint(checkpoint(deviceKey, 40, headAfter(frames, 40, NONCE)), trust));
        lock.bindCapture(headAfter(frames, 40, NONCE));
        LockOutcome outcome = lock.check(null, null, null);
        assertTrue(outcome.passed(), outcome.toString());
        assertEquals(BindingLevel.CRYPTOGRAPHIC, SignedStreamLock.level(true, outcome));
    }

    @Test
    void aDroppedFrameBreaksTheChainAtTheNextFrame() {
        List<Sent> frames = stream(12, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        List<Sent> received = new ArrayList<>(frames);
        received.remove(4); // the attacker removes one frame
        assertFalse(feed(lock, received));
        assertEquals(5, lock.brokenAt(), "the receiver's 5th frame is the device's 6th");
        lock.bindCapture(frames.get(frames.size() - 1).tag);
        assertEquals("STREAM_TAG_MISMATCH", lock.check(null, null, null).code());
    }

    @Test
    void reorderedFramesBreakTheChain() {
        List<Sent> frames = stream(12, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        List<Sent> received = new ArrayList<>(frames);
        Collections.swap(received, 3, 4);
        assertFalse(feed(lock, received));
        assertEquals("STREAM_TAG_MISMATCH", lock.check(null, null, null).code());
    }

    @Test
    void aReplacedFrameKeepingTheOriginalTagBreaksTheChain() {
        List<Sent> frames = stream(12, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        List<Sent> received = new ArrayList<>(frames);
        byte[] forged = received.get(6).frame.clone();
        forged[10] ^= 0x01; // one bit of an injected image
        received.set(6, new Sent(forged, received.get(6).t, received.get(6).tag));
        assertFalse(feed(lock, received));
        assertEquals(7, lock.brokenAt());
    }

    @Test
    void aRetimedFrameBreaksTheChain() {
        List<Sent> frames = stream(12, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        List<Sent> received = new ArrayList<>(frames);
        received.set(2, new Sent(received.get(2).frame, received.get(2).t + 40, received.get(2).tag));
        assertFalse(feed(lock, received));
    }

    @Test
    void onceBrokenTheStreamStaysBroken() {
        List<Sent> frames = stream(12, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        lock.observe(frames.get(0).frame, frames.get(0).t, new byte[32]);
        assertFalse(lock.observe(frames.get(1).frame, frames.get(1).t, frames.get(1).tag));
        assertEquals(1, lock.brokenAt());
    }

    @Test
    void aStreamFromAnotherTransactionFailsAtTheFirstFrame() {
        List<Sent> replayed = stream(5, "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII));
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        assertFalse(lock.observe(replayed.get(0).frame, replayed.get(0).t, replayed.get(0).tag));
        assertEquals(1, lock.brokenAt());
    }

    @Test
    void aCaptureNamingAnEarlierHeadIsRefused() {
        List<Sent> frames = stream(30, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        assertTrue(feed(lock, frames));
        lock.bindCapture(headAfter(frames, 20, NONCE)); // a capture signed against a stale part of the stream
        assertEquals("STREAM_HEAD_MISMATCH", lock.check(null, null, null).code());
    }

    @Test
    void aCaptureWithNoStreamHeadIsRefusedAndStaysHeuristic() {
        List<Sent> frames = stream(10, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        feed(lock, frames);
        lock.bindCapture(null);
        LockOutcome outcome = lock.check(null, null, null);
        assertEquals("STREAM_HEAD_MISSING", outcome.code());
        assertEquals(BindingLevel.HEURISTIC, SignedStreamLock.level(true, outcome));
        assertEquals(BindingLevel.HEURISTIC, SignedStreamLock.level(false, null), "no profile, no cryptographic level");
    }

    @Test
    void aCheckpointFromAnUntrustedKeyOrWithAWrongTagIsRejected() throws Exception {
        List<Sent> frames = stream(20, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE);
        feed(lock, frames);
        byte[] head = headAfter(frames, 20, NONCE);
        assertFalse(lock.acceptCheckpoint(checkpoint(strangerKey, 20, head), trust), "signed by someone else");
        assertFalse(lock.acceptCheckpoint(checkpoint(deviceKey, 20, headAfter(frames, 19, NONCE)), trust),
                "names a tag the client did not compute for that index");
        assertFalse(lock.acceptCheckpoint(checkpoint(deviceKey, 99, head), trust), "an index past the stream");
        assertTrue(lock.acceptCheckpoint(checkpoint(deviceKey, 20, head), trust));
    }

    @Test
    void aStreamTooLongWithoutASignedCheckpointStopsBeingTrusted() throws Exception {
        List<Sent> frames = stream(100, NONCE);
        SignedStreamLock lock = new SignedStreamLock(KS, NONCE, 60);
        feed(lock, frames);
        lock.bindCapture(headAfter(frames, 100, NONCE));
        assertEquals("STREAM_UNSIGNED", lock.check(null, null, null).code());
        assertTrue(lock.acceptCheckpoint(checkpoint(deviceKey, 70, headAfter(frames, 70, NONCE)), trust));
        assertTrue(lock.check(null, null, null).passed());
    }
}
