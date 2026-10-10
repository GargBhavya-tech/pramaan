/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.transparency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.testing.TestClock;

/** Build Map #64 (Bible Part 42): the Certificate Transparency test vectors, every proof, and tamper detection. */
class MerkleLogTest {

    /** The eight leaves of the RFC 6962 reference test data (certificate-transparency merkle_tree_test). */
    static final byte[][] CT_LEAVES = {
        {},
        {0x00},
        {0x10},
        {0x20, 0x21},
        {0x30, 0x31},
        {0x40, 0x41, 0x42, 0x43},
        {0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57},
        {0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x6f},
    };
    static final String[] CT_ROOTS = {
        "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
        "fac54203e7cc696cf0dfcb42c92a1d9dbaf70ad9e621f4bd8d98662f00e3c125",
        "aeb6bcfe274b70a14fb067a5e5578264db0fa9b51af5e0ba159158f329e06e77",
        "d37ee418976dd95753c1c73862b9398fa2a2cf9b4ff0fdfe8b30cd95209614b7",
        "4e3bbb1f7b478dcfe71fb631631519a3bca12c9aefca1612bfce4c13a86264d4",
        "76e67dadbcdf1e10e1b74ddc608abd2f98dfb16fbce75277b5232a127f2087ef",
        "ddb89be403809e325750d3d263cd78929c2942b7942a34b77e122c9594a74c8c",
        "5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328",
    };

    static MerkleLog log(int n) throws Exception {
        MerkleLog log = MerkleLog.inMemory();
        for (int i = 0; i < n; i++) {
            log.append(entry(i));
        }
        return log;
    }

    static byte[] entry(int i) {
        return ("attempt-" + i).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void rootsMatchTheCertificateTransparencyReferenceVectors() throws Exception {
        MerkleLog log = MerkleLog.inMemory();
        for (int i = 0; i < CT_LEAVES.length; i++) {
            log.append(CT_LEAVES[i]);
            assertEquals(CT_ROOTS[i], MerkleLog.hex(log.root()), "root of the first " + (i + 1) + " leaves");
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", MerkleLog.hex(log.root(0)),
                "the empty tree is SHA-256 of nothing");
    }

    @Test
    void everyInclusionProofVerifiesAndAWrongOneDoesNot() throws Exception {
        MerkleLog log = log(20);
        for (int n = 1; n <= 20; n++) {
            byte[] root = log.root(n);
            for (int m = 0; m < n; m++) {
                List<byte[]> p = log.inclusionProof(m, n);
                assertTrue(MerkleLog.verifyInclusion(MerkleLog.leafHash(entry(m)), m, n, p, root), m + " in " + n);
                assertTrue(p.size() <= 32 - Integer.numberOfLeadingZeros(n), "about log2(n) hashes");
                assertFalse(MerkleLog.verifyInclusion(MerkleLog.leafHash(entry(m + 100)), m, n, p, root), "wrong leaf");
                if (n > 1) {
                    assertFalse(MerkleLog.verifyInclusion(MerkleLog.leafHash(entry(m)), (m + 1) % n, n, p, root), "wrong index");
                }
            }
        }
    }

    @Test
    void everyConsistencyProofVerifies() throws Exception {
        MerkleLog log = log(20);
        for (int n = 1; n <= 20; n++) {
            for (int m = 1; m <= n; m++) {
                assertTrue(MerkleLog.verifyConsistency(m, n, log.root(m), log.root(n), log.consistencyProof(m, n)), m + " -> " + n);
            }
        }
    }

    @Test
    void alteringOrDeletingAPastAttemptBreaksConsistency() throws Exception {
        MerkleLog honest = log(10);
        byte[] yesterday = honest.root(6);
        // A machine that altered attempt 3, or deleted it, then carried on appending.
        MerkleLog altered = MerkleLog.inMemory();
        MerkleLog deleted = MerkleLog.inMemory();
        for (int i = 0; i < 11; i++) {
            altered.append(i == 3 ? entry(999) : entry(i));
            if (i != 3) {
                deleted.append(entry(i));
            }
        }
        for (MerkleLog tampered : List.of(altered, deleted)) {
            long n = tampered.size();
            assertFalse(MerkleLog.verifyConsistency(6, n, yesterday, tampered.root(n), tampered.consistencyProof(6, n)));
        }
        assertTrue(MerkleLog.verifyConsistency(6, 10, yesterday, honest.root(10), honest.consistencyProof(6, 10)));
    }

    @Test
    void treeHeadsCatchRollbackShrinkingAndRewrites() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair machine = g.generateKeyPair();
        JwsTrust trust = JwsTrust.builder(TestClock.at("2026-09-23T10:00:00Z")).key("machine", machine.getPublic()).build();
        Instant t = Instant.parse("2026-09-23T10:00:00Z");
        MerkleLog log = log(6);
        SignedTreeHead day1 = SignedTreeHead.verify(SignedTreeHead.sign(log, t, 1, machine.getPrivate(), "machine").jws(), trust);
        for (int i = 6; i < 10; i++) {
            log.append(entry(i));
        }
        SignedTreeHead day2 = SignedTreeHead.sign(log, t.plusSeconds(86_400), 2, machine.getPrivate(), "machine");
        assertEquals(TreeHeadChecker.Verdict.CONSISTENT, TreeHeadChecker.check(day1, day2, log.consistencyProof(6, 10)));

        SignedTreeHead rolledBack = SignedTreeHead.sign(log, t.plusSeconds(90_000), 1, machine.getPrivate(), "machine");
        assertEquals(TreeHeadChecker.Verdict.COUNTER_ROLLBACK, TreeHeadChecker.check(day1, rolledBack, log.consistencyProof(6, 10)));

        MerkleLog wiped = log(4);
        SignedTreeHead shrunk = SignedTreeHead.sign(wiped, t.plusSeconds(90_000), 3, machine.getPrivate(), "machine");
        assertEquals(TreeHeadChecker.Verdict.TREE_SHRANK, TreeHeadChecker.check(day1, shrunk, List.of()));

        MerkleLog rewritten = MerkleLog.inMemory();
        for (int i = 0; i < 10; i++) {
            rewritten.append(i == 2 ? entry(777) : entry(i));
        }
        SignedTreeHead forged = SignedTreeHead.sign(rewritten, t.plusSeconds(90_000), 4, machine.getPrivate(), "machine");
        assertEquals(TreeHeadChecker.Verdict.INCONSISTENT,
                TreeHeadChecker.check(day1, forged, rewritten.consistencyProof(6, 10)), "a deleted failure is detected");
    }

    @Test
    void aMachinesFirstTreeHeadAfterAnEmptyOneIsConsistent() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair machine = g.generateKeyPair();
        Instant t = Instant.parse("2026-09-23T10:00:00Z");
        SignedTreeHead empty = SignedTreeHead.sign(MerkleLog.inMemory(), t, 1, machine.getPrivate(), "machine");
        SignedTreeHead first = SignedTreeHead.sign(log(3), t.plusSeconds(60), 2, machine.getPrivate(), "machine");
        assertEquals(TreeHeadChecker.Verdict.CONSISTENT, TreeHeadChecker.check(empty, first, List.of()));
        assertEquals(TreeHeadChecker.Verdict.COUNTER_ROLLBACK, TreeHeadChecker.check(first, empty, List.of()));
    }

    @Test
    void aFileBackedLogSurvivesARestartUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("attempts.log");
        MerkleLog first = MerkleLog.open(file);
        for (int i = 0; i < 7; i++) {
            first.append(entry(i));
        }
        MerkleLog reopened = MerkleLog.open(file);
        assertEquals(7, reopened.size());
        assertEquals(MerkleLog.hex(first.root()), MerkleLog.hex(reopened.root()));
        assertThrows(IllegalArgumentException.class, () -> reopened.inclusionProof(7, 7));
    }
}
