/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;

/** Build Map #54-#57: each lock fails on its own, and every failure rejects. */
class CaptureBinderTest {

    static final String KID = "test-device";
    static final String NONCE = "0123456789abcdef0123456789abcdef";
    static final String HEAD = "a".repeat(64);
    static final Instant T = Instant.parse("2026-09-23T10:00:05Z");
    static KeyPair device;
    static KeyPair stranger;
    static TestClock clock;
    static CaptureBinder binder;
    static final float[] FACE = unit(0);
    static final float[] OTHER = unit(64);

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        device = g.generateKeyPair();
        stranger = g.generateKeyPair();
        clock = TestClock.at("2026-09-23T10:00:05Z");
        binder = new CaptureBinder(JwsTrust.builder(clock).key(KID, device.getPublic()).build(), 0.363,
                Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    static float[] unit(int at) {
        float[] v = new float[SFaceEmbedder.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = 0.01f;
        }
        v[at] = 1;
        return Embeddings.normalize(v);
    }

    static byte[] iso(int seed) {
        byte[] b = new byte[64];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i * 7 + seed);
        }
        return b;
    }

    /** A real device-signed capture, chained onto {@code previousHash}. */
    static SignedCapture capture(KeyPair key, String nonce, String timestamp, byte[] iso, String previousHash,
            Instant receivedAt) throws Exception {
        String payload = "{\"transactionId\":\"" + nonce + "\",\"timestamp\":\"" + timestamp + "\",\"bioValue\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(iso) + "\"}";
        String jws = Jws.signRs256(payload.getBytes(StandardCharsets.UTF_8), KID, key.getPrivate());
        return new SignedCapture(jws, SbiCaptureChain.expectedHash(previousHash, iso), previousHash, nonce, timestamp, KID,
                new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1"), receivedAt);
    }

    static SignedCapture good() throws Exception {
        return capture(device, NONCE, "2026-09-23T10:00:04Z", iso(1), "", T);
    }

    static BindingInput input(SignedCapture c, float[] captured, long currentTrack, Instant lastFrame, List<SignedCapture> earlier) {
        return new BindingInput(NONCE, HEAD, 7, currentTrack, lastFrame, T.minusMillis(700), List.of(FACE, FACE, FACE),
                captured, c, earlier);
    }

    static BindingInput input(SignedCapture c, float[] captured, long currentTrack, Instant lastFrame, List<SignedCapture> earlier,
            float[] operatorEmbedding) {
        return new BindingInput(NONCE, HEAD, 7, currentTrack, lastFrame, T.minusMillis(700), List.of(FACE, FACE, FACE),
                captured, c, earlier, operatorEmbedding);
    }

    private static Map<String, String> codes(BindingResult r) {
        return r.outcomes().stream().collect(Collectors.toMap(LockOutcome::lock, LockOutcome::code));
    }

    private static void onlyFails(BindingResult r, String lock, String code) {
        assertFalse(r.passed(), r.summary());
        Map<String, String> c = codes(r);
        assertEquals(code, c.get(lock), r.summary());
        c.forEach((l, v) -> {
            if (!l.equals(lock)) {
                assertEquals("OK", v, "only " + lock + " may fail: " + r.summary());
            }
        });
    }

    @Test
    void theWorkedExampleBinds() throws Exception {
        BindingResult r = binder.bind(input(good(), FACE, 7, T.minusMillis(1400), List.of()));
        assertTrue(r.passed(), r.summary());
        assertNotNull(r.finalHash());
        assertEquals(64, r.finalHash().length());
        assertEquals(NONCE, r.nonce());
    }

    // ---------------------------------------------------------------- lock 1
    @Test
    void anOldCaptureWithAValidSignatureButAnotherNonceIsRejected() throws Exception {
        SignedCapture replay = capture(device, "ffffffffffffffffffffffffffffffff", "2026-09-23T10:00:04Z", iso(1), "", T);
        onlyFails(binder.bind(input(replay, FACE, 7, T.minusMillis(1400), List.of())), "nonce", "NONCE_MISMATCH");
    }

    @Test
    void aStaleSignedTimestampIsRejected() throws Exception {
        SignedCapture old = capture(device, NONCE, "2026-09-23T09:58:00Z", iso(1), "", T);
        onlyFails(binder.bind(input(old, FACE, 7, T.minusMillis(1400), List.of())), "nonce", "STALE_TIMESTAMP");
    }

    @Test
    void anUntrustedOrTamperedSignatureIsRejectedBeforeAnyFieldIsRead() throws Exception {
        SignedCapture foreign = capture(stranger, NONCE, "2026-09-23T10:00:04Z", iso(1), "", T);
        BindingResult r = binder.bind(input(foreign, FACE, 7, T.minusMillis(1400), List.of()));
        assertEquals("SIGNATURE_INVALID", codes(r).get("nonce"));
        assertEquals("UNVERIFIED_CAPTURE", codes(r).get("chain"));
        assertNull(r.finalHash());

        SignedCapture g = good();
        String[] parts = g.signedData().split("\\.");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"transactionId\":\"" + NONCE + "\",\"timestamp\":\"2026-09-23T10:00:04Z\",\"bioValue\":\"AAAA\"}")
                        .getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        SignedCapture tampered = new SignedCapture(forged, g.chainHash(), "", NONCE, "x", KID, g.device(), T);
        assertEquals("SIGNATURE_INVALID", codes(binder.bind(input(tampered, FACE, 7, T.minusMillis(1400), List.of()))).get("nonce"));
    }

    // ---------------------------------------------------------------- lock 2 + 5
    @Test
    void aHashThatDoesNotRecomputeFromTheSignedRecordIsRejected() throws Exception {
        SignedCapture g = good();
        SignedCapture wrongHash = new SignedCapture(g.signedData(), SbiCaptureChain.expectedHash("", iso(2)), "", NONCE,
                g.verifiedTimestamp(), KID, g.device(), T);
        onlyFails(binder.bind(input(wrongHash, FACE, 7, T.minusMillis(1400), List.of())), "chain", "CHAIN_HASH_MISMATCH");
    }

    @Test
    void aRemovedOrReorderedCaptureBreaksTheSbiChain() throws Exception {
        SignedCapture first = capture(device, NONCE, "2026-09-23T10:00:01Z", iso(10), "", T);
        SignedCapture second = capture(device, NONCE, "2026-09-23T10:00:02Z", iso(11), first.chainHash(), T);
        SignedCapture third = capture(device, NONCE, "2026-09-23T10:00:04Z", iso(12), second.chainHash(), T);
        assertTrue(binder.bind(input(third, FACE, 7, T.minusMillis(1400), List.of(first, second))).passed());
        onlyFails(binder.bind(input(third, FACE, 7, T.minusMillis(1400), List.of(first))), "chain", "CHAIN_BROKEN");
        onlyFails(binder.bind(input(third, FACE, 7, T.minusMillis(1400), List.of(second, first))), "chain", "CHAIN_BROKEN");
    }

    @Test
    void theFinalHashIsStableAndBindsBothChains() throws Exception {
        SignedCapture g = good();
        String a = binder.bind(input(g, FACE, 7, T.minusMillis(1400), List.of())).finalHash();
        assertEquals(a, binder.bind(input(g, FACE, 7, T.minusMillis(1400), List.of())).finalHash());
        assertEquals(a, SbiCaptureChain.finalHash(HEAD, g.signedData()));
        assertFalse(a.equals(SbiCaptureChain.finalHash("b".repeat(64), g.signedData())), "our chain head is in it");
    }

    // ---------------------------------------------------------------- lock 3
    @Test
    void aDifferentFaceInTheCaptureIsRejected() throws Exception {
        BindingResult r = binder.bind(input(good(), OTHER, 7, T.minusMillis(1400), List.of()));
        onlyFails(r, "identity", "IDENTITY_MISMATCH");
        assertTrue(r.outcomes().get(2).value() < 0.363);
    }

    @Test
    void aTrackChangeOrAMissingFaceIsRejected() throws Exception {
        onlyFails(binder.bind(input(good(), FACE, 8, T.minusMillis(1400), List.of())), "identity", "TRACK_CHANGED");
        onlyFails(binder.bind(input(good(), null, 7, T.minusMillis(1400), List.of())), "identity", "NO_FACE_IN_CAPTURE");
    }

    // ---------------------------------------------------------------- lock 4
    @Test
    void aCaptureRequestedTooLongAfterTheLastVerifiedFrameIsRejected() throws Exception {
        onlyFails(binder.bind(input(good(), FACE, 7, T.minusMillis(2701), List.of())), "time", "TIME_GAP");
        assertTrue(binder.bind(input(good(), FACE, 7, T.minusMillis(2700), List.of())).passed(), "exactly 2 s is allowed");
    }

    // ---------------------------------------------------------------- helpers under the locks
    @Test
    void theSbiHashMatchesTheDocumentedFormula() {
        // SHA-256("") raw || SHA-256(record) raw, upper-case hex — the formula confirmed on real MockMDS output.
        byte[] rec = "record".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = SbiCaptureChain.sha256(concat(SbiCaptureChain.sha256(new byte[0]), SbiCaptureChain.sha256(rec)));
        assertEquals(SbiCaptureChain.hex(expected).toUpperCase(java.util.Locale.ROOT), SbiCaptureChain.expectedHash("", rec));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void medianEmbeddingIgnoresAMinorityOfBadFrames() {
        List<float[]> refs = new ArrayList<>(List.of(FACE, FACE, FACE, OTHER));
        assertTrue(Embeddings.cosine(Embeddings.median(refs), FACE) > 0.99);
    }

    @Test
    void theIsoParserFindsTheImageByItsSelfConsistentLength() throws IOException {
        byte[] jp2 = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 13, 10, (byte) 0x87, 10, 1, 2, 3};
        byte[] head = new byte[40];
        // Not "FAC\0030\0": Java reads \003 as one octal escape, and the header comes out six bytes long.
        System.arraycopy(new byte[] {'F', 'A', 'C', 0, '0', '3', '0', 0}, 0, head, 0, 8);
        int total = head.length + 4 + jp2.length;
        head[8] = (byte) (total >> 24);
        head[9] = (byte) (total >> 16);
        head[10] = (byte) (total >> 8);
        head[11] = (byte) total;
        head[13] = 1;
        byte[] len = {0, 0, 0, (byte) jp2.length};
        byte[] rec = concat(concat(head, len), jp2);
        IsoFaceRecord r = IsoFaceRecord.parse(rec);
        assertEquals(IsoFaceRecord.ImageFormat.JPEG2000, r.format());
        assertEquals(jp2.length, r.image().length);
        byte[] two = rec.clone();
        two[13] = 2;
        assertThrows(IOException.class, () -> IsoFaceRecord.parse(two), "two faces in one record");
        byte[] lying = rec.clone();
        lying[head.length + 3] = (byte) (jp2.length + 5);
        assertThrows(IOException.class, () -> IsoFaceRecord.parse(lying), "declared length must reach the end");
    }

    // ---------------------------------------------------------------- lock 6 (operator guard, Build Map #88)
    @Test
    void aResidentCaptureMatchingTheLoggedInOperatorsFaceFailsTheOperatorLockOnly() throws Exception {
        BindingResult r = binder.bind(input(good(), FACE, 7, T.minusMillis(1400), List.of(), FACE));
        onlyFails(r, "operator", "OPERATOR_FACE");
        assertNotNull(r.finalHash(), "the chain still binds; only the operator lock fails");
    }

    @Test
    void aDifferentFaceFromTheOperatorPassesTheOperatorLock() throws Exception {
        BindingResult r = binder.bind(input(good(), FACE, 7, T.minusMillis(1400), List.of(), OTHER));
        assertTrue(r.passed(), r.summary());
    }

    @Test
    void noOperatorEmbeddingMeansNothingToGuardAgainst() throws Exception {
        // Operator-workflow sessions and any binding without guardAgainstOperatorFace pass null here.
        BindingResult r = binder.bind(input(good(), FACE, 7, T.minusMillis(1400), List.of(), null));
        assertTrue(r.passed(), r.summary());
    }

    @Test
    void theOperatorLockNeverBlocksACaptureWithNoFaceItAlreadyFailsLock3() throws Exception {
        BindingResult r = binder.bind(input(good(), null, 7, T.minusMillis(1400), List.of(), FACE));
        onlyFails(r, "identity", "NO_FACE_IN_CAPTURE");
    }

    @Test
    void theAlignerMapsTheReferencePointsOntoThemselves() {
        double[] m = FaceAligner.similarity(FaceAligner.REFERENCE, FaceAligner.REFERENCE);
        assertEquals(1, m[0], 1e-12);
        assertEquals(0, m[1], 1e-12);
        assertEquals(0, m[2], 1e-9);
        assertEquals(0, m[3], 1e-9);
        double[] scaled = new double[10];
        for (int i = 0; i < 10; i += 2) {
            scaled[i] = FaceAligner.REFERENCE[i] * 2 + 30;
            scaled[i + 1] = FaceAligner.REFERENCE[i + 1] * 2 + 40;
        }
        double[] back = FaceAligner.similarity(scaled, FaceAligner.REFERENCE);
        assertEquals(0.5, back[0], 1e-12);
        assertEquals(-15, back[2], 1e-9);
        assertEquals(-20, back[3], 1e-9);
    }
}
