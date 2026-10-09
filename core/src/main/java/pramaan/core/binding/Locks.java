/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The four locks of Bible Part 13. */
final class Locks {

    private Locks() {
    }

    /** Lock 1 (#54): the device signed our nonce, recently. */
    static final class NonceLock implements BindingLock {
        static final String NAME = "nonce";
        private final Duration maxSkew;

        NonceLock(Duration maxSkew) {
            this.maxSkew = maxSkew;
        }

        @Override
        public LockOutcome check(BindingInput in, VerifiedCapture current, List<VerifiedCapture> earlier) {
            if (current == null) {
                return LockOutcome.fail(NAME, "SIGNATURE_INVALID", Double.NaN);
            }
            if (!in.nonce().equals(current.transactionId())) {
                return LockOutcome.fail(NAME, "NONCE_MISMATCH", Double.NaN);
            }
            Instant signed;
            try {
                signed = OffsetDateTime.parse(current.timestamp()).toInstant();
            } catch (DateTimeParseException e) {
                return LockOutcome.fail(NAME, "TIMESTAMP_UNREADABLE", Double.NaN);
            }
            long skew = Math.abs(Duration.between(signed, current.capture().receivedAt()).toMillis());
            if (skew > maxSkew.toMillis()) {
                return LockOutcome.fail(NAME, "STALE_TIMESTAMP", skew);
            }
            return LockOutcome.pass(NAME, skew);
        }
    }

    /** Lock 2 + 5 (#55): our frame chain exists, and MOSIP's capture chain recomputes and links. */
    static final class ChainLock implements BindingLock {
        static final String NAME = "chain";

        @Override
        public LockOutcome check(BindingInput in, VerifiedCapture current, List<VerifiedCapture> earlier) {
            if (current == null || earlier == null) {
                return LockOutcome.fail(NAME, "UNVERIFIED_CAPTURE", Double.NaN);
            }
            List<VerifiedCapture> all = new ArrayList<>(earlier);
            all.add(current);
            for (int i = 0; i < all.size(); i++) {
                VerifiedCapture c = all.get(i);
                String expected = SbiCaptureChain.expectedHash(c.capture().previousHash(), c.isoRecord());
                if (!expected.equalsIgnoreCase(c.capture().chainHash())) {
                    return LockOutcome.fail(NAME, "CHAIN_HASH_MISMATCH", i);
                }
                if (i > 0 && !c.capture().previousHash().equalsIgnoreCase(all.get(i - 1).capture().chainHash())) {
                    return LockOutcome.fail(NAME, "CHAIN_BROKEN", i);
                }
            }
            return LockOutcome.pass(NAME, all.size());
        }
    }

    /** Lock 3 (#56): the same face, on the same unbroken track. */
    static final class IdentityLock implements BindingLock {
        static final String NAME = "identity";
        private final double thetaSame;

        IdentityLock(double thetaSame) {
            this.thetaSame = thetaSame;
        }

        @Override
        public LockOutcome check(BindingInput in, VerifiedCapture current, List<VerifiedCapture> earlier) {
            if (in.evidenceTrackId() == 0 || in.currentTrackId() != in.evidenceTrackId()) {
                return LockOutcome.fail(NAME, "TRACK_CHANGED", Double.NaN);
            }
            if (in.referenceEmbeddings().isEmpty()) {
                return LockOutcome.fail(NAME, "NO_REFERENCE_EMBEDDINGS", Double.NaN);
            }
            float[] captured = in.captureEmbedding();
            if (captured == null) {
                return LockOutcome.fail(NAME, "NO_FACE_IN_CAPTURE", Double.NaN);
            }
            double s = Embeddings.cosine(captured, Embeddings.median(in.referenceEmbeddings()));
            return s >= thetaSame ? LockOutcome.pass(NAME, s) : LockOutcome.fail(NAME, "IDENTITY_MISMATCH", s);
        }
    }

    /** Lock 4 (#57): the face was live-verified no more than the window before the capture was requested. */
    static final class TimeLock implements BindingLock {
        static final String NAME = "time";
        private final Duration window;

        TimeLock(Duration window) {
            this.window = window;
        }

        @Override
        public LockOutcome check(BindingInput in, VerifiedCapture current, List<VerifiedCapture> earlier) {
            if (in.lastEvidenceFrameAt() == null) {
                return LockOutcome.fail(NAME, "NO_EVIDENCE_FRAMES", Double.NaN);
            }
            long gap = Duration.between(in.lastEvidenceFrameAt(), in.captureRequestedAt()).toMillis();
            return gap <= window.toMillis() ? LockOutcome.pass(NAME, gap) : LockOutcome.fail(NAME, "TIME_GAP", gap);
        }
    }

    /**
     * Lock 6 (Bible Part 15.3, 18): a resident capture must not be the logged-in operator's own face. Passes when
     * there is no operator face to compare, or no face in the capture (lock 3 already fails that).
     */
    static final class OperatorLock implements BindingLock {
        static final String NAME = "operator";
        private final double thetaSame;

        OperatorLock(double thetaSame) {
            this.thetaSame = thetaSame;
        }

        @Override
        public LockOutcome check(BindingInput in, VerifiedCapture current, List<VerifiedCapture> earlier) {
            float[] operator = in.operatorEmbedding();
            float[] captured = in.captureEmbedding();
            if (operator == null || captured == null) {
                return LockOutcome.pass(NAME, Double.NaN);
            }
            double s = Embeddings.cosine(captured, operator);
            return s >= thetaSame ? LockOutcome.fail(NAME, "OPERATOR_FACE", s) : LockOutcome.pass(NAME, s);
        }
    }

    static String describe(List<LockOutcome> outcomes) {
        StringBuilder s = new StringBuilder();
        for (LockOutcome o : outcomes) {
            if (s.length() > 0) {
                s.append(',');
            }
            s.append(o.lock()).append('=').append(o.code().toLowerCase(Locale.ROOT));
        }
        return s.toString();
    }
}
