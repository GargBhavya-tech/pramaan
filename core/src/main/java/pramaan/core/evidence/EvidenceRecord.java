/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A {@code pramaan.evidence/1} record (Bible Part 14; Build Map #59): what the client decided and why, in a
 * form a server can re-check. Immutable; its canonical bytes are what gets signed. Trust never comes from
 * how a record was built, only from verifying its signature ({@link EvidenceSigner#verify}).
 */
public final class EvidenceRecord {

    public static final String SCHEMA = "pramaan.evidence/1";
    /** Size budget for everything except the encrypted sample crops (Bible Part 14: ~2-4 KB). */
    public static final int JSON_BUDGET_BYTES = 4096;
    /** Size budget for the encrypted crops (Bible Part 14: ~15-25 KB). */
    public static final int SAMPLES_BUDGET_BYTES = 25 * 1024;

    private final ObjectNode json;
    private final byte[] canonical;

    EvidenceRecord(ObjectNode json) {
        this.json = json.deepCopy();
        this.canonical = CanonicalJson.write(this.json);
    }

    /** A record from JSON, for tooling and tests; production records come from {@link EvidenceBuilder}. */
    public static EvidenceRecord of(ObjectNode json) {
        return new EvidenceRecord(json);
    }

    public byte[] canonical() {
        return canonical.clone();
    }

    /** A copy of the record's JSON. */
    public ObjectNode json() {
        return json.deepCopy();
    }

    public String text(String... path) {
        JsonNode n = json;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /** Bytes of the record without its encrypted samples, for the JSON budget. */
    public int sizeWithoutSamples() {
        ObjectNode copy = json.deepCopy();
        copy.remove("samples");
        return CanonicalJson.write(copy).length;
    }

    /** Bytes of the encrypted samples as serialised. */
    public int samplesSize() {
        JsonNode s = json.get("samples");
        return s == null ? 0 : CanonicalJson.write(s).length;
    }
}
