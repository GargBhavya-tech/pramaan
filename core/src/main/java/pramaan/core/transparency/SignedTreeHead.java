/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.transparency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Instant;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.evidence.CanonicalJson;

/**
 * A Signed Tree Head (Bible Part 42): tree size, root, time and a monotonic counter, signed by the machine
 * key. The counter only goes up, so rolling the clock back cannot rewind the log's history.
 */
public final class SignedTreeHead {

    private final long treeSize;
    private final String rootHash;
    private final Instant timestamp;
    private final long counter;
    private final String jws;

    private SignedTreeHead(long treeSize, String rootHash, Instant timestamp, long counter, String jws) {
        this.treeSize = treeSize;
        this.rootHash = rootHash;
        this.timestamp = timestamp;
        this.counter = counter;
        this.jws = jws;
    }

    public static SignedTreeHead sign(MerkleLog log, Instant now, long counter, PrivateKey key, String kid)
            throws GeneralSecurityException {
        long size = log.size();
        String root = MerkleLog.hex(log.root(size));
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("treeSize", size);
        o.put("rootHash", root);
        o.put("timestamp", now.toString());
        o.put("counter", counter);
        return new SignedTreeHead(size, root, now, counter, Jws.signRs256(CanonicalJson.write(o), kid, key));
    }

    /** Verifies the signature before reading anything. */
    public static SignedTreeHead verify(String jws, JwsTrust trust) throws JwsVerificationException {
        byte[] payload = Jws.verify(jws, trust).payloadBytes();
        if (!CanonicalJson.isCanonical(payload)) {
            throw new JwsVerificationException("tree head is not canonical JSON");
        }
        try {
            JsonNode n = CanonicalJson.parse(payload);
            return new SignedTreeHead(n.path("treeSize").asLong(-1), n.path("rootHash").asText(""),
                    Instant.parse(n.path("timestamp").asText()), n.path("counter").asLong(-1), jws);
        } catch (IOException | RuntimeException e) {
            throw new JwsVerificationException("tree head payload unreadable: " + e.getMessage(), e);
        }
    }

    public long treeSize() {
        return treeSize;
    }

    public String rootHash() {
        return rootHash;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public long counter() {
        return counter;
    }

    public String jws() {
        return jws;
    }
}
