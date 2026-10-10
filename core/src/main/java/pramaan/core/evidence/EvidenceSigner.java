/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.Objects;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;

/**
 * Signs evidence as a compact RS256 JWS over its canonical bytes (Build Map #59), and verifies it again.
 * In production the key is the Registration Client machine key (TPM-backed on desktop; Bible Part 30, Q9);
 * development and tests use a generated key.
 */
public final class EvidenceSigner {

    private final PrivateKey key;
    private final String kid;

    public EvidenceSigner(PrivateKey key, String kid) {
        this.key = Objects.requireNonNull(key, "key");
        this.kid = Objects.requireNonNull(kid, "kid");
    }

    public String sign(EvidenceRecord record) throws GeneralSecurityException {
        return Jws.signRs256(record.canonical(), kid, key);
    }

    /** Signature first, then canonical form, then schema; nothing is read before the signature holds. */
    public static EvidenceRecord verify(String jws, JwsTrust trust) throws EvidenceException {
        byte[] payload;
        try {
            payload = Jws.verify(jws, trust).payloadBytes();
        } catch (JwsVerificationException e) {
            throw new EvidenceException("evidence signature rejected: " + e.getMessage(), e);
        }
        if (!CanonicalJson.isCanonical(payload)) {
            throw new EvidenceException("evidence payload is not in canonical form", null);
        }
        try {
            JsonNode n = CanonicalJson.parse(payload);
            if (!n.isObject() || !EvidenceRecord.SCHEMA.equals(n.path("schema").asText())) {
                throw new EvidenceException("not a " + EvidenceRecord.SCHEMA + " record", null);
            }
            return new EvidenceRecord((ObjectNode) n);
        } catch (IOException e) {
            throw new EvidenceException("evidence payload is not JSON", e);
        }
    }
}
