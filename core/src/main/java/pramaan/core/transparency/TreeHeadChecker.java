/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.transparency;

import java.util.List;

/**
 * What a server (or any auditor) does with each new tree head from a machine (Bible Part 42): the counter
 * must rise, the tree must not shrink, and a consistency proof must show the new tree extends the last one
 * it saw. Any failure is a tampering alert for that machine — a deleted or altered past attempt.
 */
public final class TreeHeadChecker {

    /** The verdict on a new tree head. */
    public enum Verdict {
        CONSISTENT,
        COUNTER_ROLLBACK,
        TREE_SHRANK,
        INCONSISTENT
    }

    private TreeHeadChecker() {
    }

    public static Verdict check(SignedTreeHead previous, SignedTreeHead next, List<byte[]> consistencyProof) {
        if (next.counter() <= previous.counter()) {
            return Verdict.COUNTER_ROLLBACK;
        }
        if (next.treeSize() < previous.treeSize()) {
            return Verdict.TREE_SHRANK;
        }
        if (previous.treeSize() == 0) {
            // Every tree extends the empty one; RFC 9162 defines no proof from size 0, so the verifier would refuse
            // it and a machine's first real tree head after an empty one would read as tampering. The empty head
            // must still carry the empty tree's root.
            return EMPTY_ROOT.equals(previous.rootHash()) ? Verdict.CONSISTENT : Verdict.INCONSISTENT;
        }
        boolean ok = MerkleLog.verifyConsistency(previous.treeSize(), next.treeSize(), unhex(previous.rootHash()),
                unhex(next.rootHash()), consistencyProof);
        return ok ? Verdict.CONSISTENT : Verdict.INCONSISTENT;
    }

    /** RFC 6962: the root of the empty tree is SHA-256 of nothing. */
    static final String EMPTY_ROOT = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
