/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * MOSIP's own capture chain (SBI {@code previousHash}; Bible Part 13, lock 5; Build Map #55):
 * {@code hash = UPPERHEX(SHA256(prev || SHA256(isoRecord)))} over raw 32-byte digests, where {@code prev}
 * is SHA-256 of the empty string for the first capture and otherwise the hex-decoded {@code previousHash}
 * that was sent. Confirmed against a real MockMDS response (docs/sbi-notes.md). The {@code hash} travels
 * outside the signature; it is trustworthy only because it is recomputed here from the <em>signed</em>
 * {@code bioValue}.
 */
public final class SbiCaptureChain {

    private SbiCaptureChain() {
    }

    public static String expectedHash(String previousHash, byte[] isoRecord) {
        byte[] prev = previousHash == null || previousHash.isEmpty() ? sha256(new byte[0]) : unhex(previousHash);
        MessageDigest md = digest();
        md.update(prev);
        md.update(sha256(isoRecord));
        return hex(md.digest()).toUpperCase(Locale.ROOT);
    }

    /** {@code H_final = SHA256(H_n || SHA256(rcapture JWS))}: our frame chain and MOSIP's capture, bound (lowercase hex). */
    public static String finalHash(String evidenceHeadHex, String captureJws) {
        MessageDigest md = digest();
        md.update(unhex(evidenceHeadHex));
        md.update(sha256(captureJws.getBytes(StandardCharsets.US_ASCII)));
        return hex(md.digest());
    }

    static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0 || !s.matches("[0-9A-Fa-f]*")) {
            throw new IllegalArgumentException("not hex: " + s);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.toString();
    }
}
