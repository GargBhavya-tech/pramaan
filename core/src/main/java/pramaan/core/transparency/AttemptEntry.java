/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.transparency;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import pramaan.core.evidence.CanonicalJson;
import pramaan.core.evidence.EvidenceRecord;
import pramaan.core.session.AttemptRecord;
import pramaan.core.session.LivenessSession;

/**
 * What one attempt contributes to the transparency log (Bible Part 42): hashes and coarse metadata only.
 * Failed attempts are logged as well as accepted ones — that is the point: a colluding operator who tries a
 * photo five times and deletes the local traces cannot delete them from a log whose tree heads have been
 * seen. Canonical JSON, so the same attempt always produces the same leaf.
 */
public final class AttemptEntry {

    private AttemptEntry() {
    }

    /**
     * The leaf for an attempt as it ended ({@link LivenessSession#onAttemptEnded}).
     *
     * @param evidence the attempt's evidence record, or null for an attempt that produced none
     */
    public static byte[] of(AttemptRecord a, EvidenceRecord evidence) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("sessionId", a.sessionId());
        o.put("attempt", a.attempt());
        o.put("sessionNonce", a.nonce());
        o.put("workflow", a.workflow().name());
        o.put("outcome", a.outcome());
        a.failure().ifPresent(f -> o.put("failure", f.name()));
        o.put("hashChainHead", a.evidenceHead());
        putEvidence(o, evidence);
        return CanonicalJson.write(o);
    }

    /** @param evidence the attempt's evidence record, or null for an attempt that produced none */
    public static byte[] of(LivenessSession s, EvidenceRecord evidence) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("sessionId", s.sessionId());
        o.put("attempt", s.attempt());
        o.put("sessionNonce", s.nonce() == null ? "" : s.nonce().value());
        o.put("workflow", s.workflow().name());
        o.put("outcome", s.state().name());
        s.lastFailure().ifPresent(f -> o.put("failure", f.name()));
        o.put("hashChainHead", s.evidenceHead());
        putEvidence(o, evidence);
        return CanonicalJson.write(o);
    }

    private static void putEvidence(ObjectNode o, EvidenceRecord evidence) {
        if (evidence != null) {
            try {
                o.put("evidenceSha256", MerkleLog.hex(MessageDigest.getInstance("SHA-256").digest(evidence.canonical())));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
            }
        }
    }
}
