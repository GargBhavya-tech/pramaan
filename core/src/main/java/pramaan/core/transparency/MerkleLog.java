/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.transparency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * An append-only Merkle tree log of every attempt, pass or fail (Bible Part 42; Build Map #64), built exactly
 * as RFC 6962 (Certificate Transparency) specifies: {@code leaf = SHA256(0x00 || entry)},
 * {@code node = SHA256(0x01 || left || right)}, split at the largest power of two below n. Inclusion and
 * consistency proofs follow RFC 6962 section 2.1; their verification follows RFC 9162 sections 2.1.3 and 2.1.4.
 *
 * <p>Entries hold hashes and coarse metadata only — never biometric data. When backed by a file, each
 * entry is appended as one base64 line, so the log survives restarts; nothing is ever rewritten.
 */
public final class MerkleLog {

    private final List<byte[]> leaves = new ArrayList<>();
    private final Path file;

    private MerkleLog(Path file) {
        this.file = file;
    }

    public static MerkleLog inMemory() {
        return new MerkleLog(null);
    }

    /** Opens (or creates) a file-backed log and replays its entries. */
    public static MerkleLog open(Path file) throws IOException {
        MerkleLog log = new MerkleLog(file);
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                if (!line.isEmpty()) {
                    log.leaves.add(leafHash(Base64.getDecoder().decode(line)));
                }
            }
        }
        return log;
    }

    /** Appends an entry and returns its leaf index. */
    public synchronized long append(byte[] entry) throws IOException {
        if (file != null) {
            Files.write(file, (Base64.getEncoder().encodeToString(entry) + "\n").getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        }
        leaves.add(leafHash(entry));
        return leaves.size() - 1L;
    }

    public synchronized long size() {
        return leaves.size();
    }

    /** MTH of the first {@code n} entries. */
    public synchronized byte[] root(long n) {
        check(n <= leaves.size(), "tree size " + n + " beyond " + leaves.size());
        return mth(0, (int) n);
    }

    public byte[] root() {
        return root(size());
    }

    /** RFC 6962 PATH(m, D[n]): the audit path proving entry {@code m} is in the tree of size {@code n}. */
    public synchronized List<byte[]> inclusionProof(long m, long n) {
        check(n <= leaves.size() && m < n, "no inclusion proof for leaf " + m + " in size " + n);
        List<byte[]> out = new ArrayList<>();
        path((int) m, 0, (int) n, out);
        return Collections.unmodifiableList(out);
    }

    /** RFC 6962 PROOF(m, D[n]): proves the tree of size {@code n} extends the tree of size {@code m}. */
    public synchronized List<byte[]> consistencyProof(long m, long n) {
        check(0 < m && m <= n && n <= leaves.size(), "no consistency proof from " + m + " to " + n);
        List<byte[]> out = new ArrayList<>();
        subproof((int) m, 0, (int) n, true, out);
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------------------------------ RFC 6962 2.1

    private byte[] mth(int from, int to) {
        int n = to - from;
        if (n == 0) {
            return sha256(new byte[0]);
        }
        if (n == 1) {
            return leaves.get(from);
        }
        int k = largestPowerOfTwoBelow(n);
        return node(mth(from, from + k), mth(from + k, to));
    }

    private void path(int m, int from, int to, List<byte[]> out) {
        int n = to - from;
        if (n == 1) {
            return;
        }
        int k = largestPowerOfTwoBelow(n);
        if (m < k) {
            path(m, from, from + k, out);
            out.add(mth(from + k, to));
        } else {
            path(m - k, from + k, to, out);
            out.add(mth(from, from + k));
        }
    }

    private void subproof(int m, int from, int to, boolean complete, List<byte[]> out) {
        int n = to - from;
        if (m == n) {
            if (!complete) {
                out.add(mth(from, to));
            }
            return;
        }
        int k = largestPowerOfTwoBelow(n);
        if (m <= k) {
            subproof(m, from, from + k, complete, out);
            out.add(mth(from + k, to));
        } else {
            subproof(m - k, from + k, to, false, out);
            out.add(mth(from, from + k));
        }
    }

    // ------------------------------------------------------------------------------------------ RFC 9162 verification

    /** RFC 9162 2.1.3.2: does {@code path} prove {@code leafHash} is leaf {@code index} of a tree of {@code size} with {@code root}? */
    public static boolean verifyInclusion(byte[] leafHash, long index, long size, List<byte[]> path, byte[] root) {
        if (index >= size) {
            return false;
        }
        long fn = index;
        long sn = size - 1;
        byte[] r = leafHash;
        for (byte[] p : path) {
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                r = node(p, r);
                if ((fn & 1) == 0) {
                    while ((fn & 1) == 0 && fn != 0) {
                        fn >>= 1;
                        sn >>= 1;
                    }
                }
            } else {
                r = node(r, p);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(r, root);
    }

    /** RFC 9162 2.1.4.2: does {@code proof} show the tree ({@code second}, {@code secondRoot}) extends ({@code first}, {@code firstRoot})? */
    public static boolean verifyConsistency(long first, long second, byte[] firstRoot, byte[] secondRoot, List<byte[]> proof) {
        if (first == second) {
            return proof.isEmpty() && Arrays.equals(firstRoot, secondRoot);
        }
        if (first <= 0 || first > second || proof.isEmpty()) {
            return false;
        }
        List<byte[]> path = new ArrayList<>(proof);
        if ((first & (first - 1)) == 0) {
            path.add(0, firstRoot);
        }
        long fn = first - 1;
        long sn = second - 1;
        while ((fn & 1) == 1) {
            fn >>= 1;
            sn >>= 1;
        }
        byte[] fr = path.get(0);
        byte[] sr = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            byte[] c = path.get(i);
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                fr = node(c, fr);
                sr = node(c, sr);
                if ((fn & 1) == 0) {
                    while ((fn & 1) == 0 && fn != 0) {
                        fn >>= 1;
                        sn >>= 1;
                    }
                }
            } else {
                sr = node(sr, c);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return Arrays.equals(fr, firstRoot) && Arrays.equals(sr, secondRoot) && sn == 0;
    }

    // ------------------------------------------------------------------------------------------ hashing

    public static byte[] leafHash(byte[] entry) {
        MessageDigest md = digest();
        md.update((byte) 0x00);
        md.update(entry);
        return md.digest();
    }

    static byte[] node(byte[] left, byte[] right) {
        MessageDigest md = digest();
        md.update((byte) 0x01);
        md.update(left);
        md.update(right);
        return md.digest();
    }

    private static byte[] sha256(byte[] b) {
        return digest().digest(b);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    static int largestPowerOfTwoBelow(int n) {
        return Integer.highestOneBit(n - 1);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.toString();
    }
}
