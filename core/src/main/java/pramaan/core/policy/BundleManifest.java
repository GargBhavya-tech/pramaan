/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.evidence.CanonicalJson;

/**
 * The signed list of everything a liveness bundle is made of (Bible Part 17.2, "signed bundles"): the policy, the
 * operating-point table and every model, each by SHA-256, with a monotonic version and an issue time. A server key
 * signs it; the client verifies it before loading anything, so an operator who edits a threshold, swaps a model or
 * restores an older bundle is refused (attack A11).
 *
 * <pre>{"schema":"pramaan.bundle/1","bundleVersion":7,"issuedAt":"2026-09-26T00:00:00Z",
 *  "files":{"operating-points.json":"&lt;sha256&gt;","pramaan-policy.yaml":"&lt;sha256&gt;","&lt;model&gt;.onnx":"&lt;sha256&gt;"}}</pre>
 */
public final class BundleManifest {

    public static final String SCHEMA = "pramaan.bundle/1";
    public static final String POLICY_FILE = "pramaan-policy.yaml";
    public static final String TABLE_FILE = "operating-points.json";

    private final long bundleVersion;
    private final Instant issuedAt;
    private final Map<String, String> files;

    private BundleManifest(long bundleVersion, Instant issuedAt, Map<String, String> files) {
        this.bundleVersion = bundleVersion;
        this.issuedAt = issuedAt;
        this.files = Collections.unmodifiableMap(files);
    }

    /** Signs a manifest for these exact bytes (the server side; {@code signBundle} in the harness). */
    public static String sign(long bundleVersion, Instant issuedAt, byte[] policyYaml, byte[] operatingPointsJson,
            Map<String, byte[]> models, PrivateKey key, String kid) throws GeneralSecurityException {
        if (bundleVersion < 1) {
            throw new IllegalArgumentException("bundle versions start at 1");
        }
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("schema", SCHEMA);
        o.put("bundleVersion", bundleVersion);
        o.put("issuedAt", issuedAt.toString());
        ObjectNode f = o.putObject("files");
        f.put(POLICY_FILE, OperatingPointTable.sha256Hex(policyYaml));
        f.put(TABLE_FILE, OperatingPointTable.sha256Hex(operatingPointsJson));
        new TreeMap<>(models).forEach((name, bytes) -> f.put(name, OperatingPointTable.sha256Hex(bytes)));
        return Jws.signRs256(CanonicalJson.write(o), kid, key);
    }

    /**
     * Verifies the signature first, then that the manifest names exactly these bytes: the policy, the table, and
     * every model — no model missing, none extra, none altered.
     *
     * @throws InvalidPolicyException for anything that does not verify
     */
    public static BundleManifest verify(String jws, JwsTrust trust, byte[] policyYaml, byte[] operatingPointsJson,
            Map<String, byte[]> models) {
        Objects.requireNonNull(trust, "trust");
        JsonNode n;
        try {
            byte[] payload = Jws.verify(jws.trim(), trust).payloadBytes();
            if (!CanonicalJson.isCanonical(payload)) {
                throw new InvalidPolicyException("bundle manifest is not canonical JSON");
            }
            n = CanonicalJson.parse(payload);
        } catch (JwsVerificationException e) {
            throw new InvalidPolicyException("bundle signature rejected: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new InvalidPolicyException("bundle manifest unreadable: " + e.getMessage(), e);
        }
        if (!SCHEMA.equals(n.path("schema").asText())) {
            throw new InvalidPolicyException("not a " + SCHEMA + " manifest");
        }
        long version = n.path("bundleVersion").asLong(0);
        if (version < 1) {
            throw new InvalidPolicyException("bundle manifest has no version");
        }
        Instant issued;
        try {
            issued = Instant.parse(n.path("issuedAt").asText());
        } catch (DateTimeParseException e) {
            throw new InvalidPolicyException("bundle manifest has no readable issue time", e);
        }
        Map<String, String> listed = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = n.path("files").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            listed.put(e.getKey(), e.getValue().asText());
        }
        Map<String, String> actual = new TreeMap<>();
        actual.put(POLICY_FILE, OperatingPointTable.sha256Hex(policyYaml));
        actual.put(TABLE_FILE, OperatingPointTable.sha256Hex(operatingPointsJson));
        models.forEach((name, bytes) -> actual.put(name, OperatingPointTable.sha256Hex(bytes)));
        if (!listed.equals(actual)) {
            StringBuilder why = new StringBuilder();
            java.util.TreeSet<String> names = new java.util.TreeSet<>(listed.keySet());
            names.addAll(actual.keySet());
            for (String name : names) {
                if (!Objects.equals(listed.get(name), actual.get(name))) {
                    why.append(listed.containsKey(name) ? actual.containsKey(name) ? " " + name + " altered;"
                            : " " + name + " missing;" : " " + name + " not in the signed bundle;");
                }
            }
            throw new InvalidPolicyException("bundle files do not match the signed manifest:" + why);
        }
        return new BundleManifest(version, issued, listed);
    }

    public long bundleVersion() {
        return bundleVersion;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    /** File name to lowercase hex SHA-256. */
    public Map<String, String> files() {
        return files;
    }
}
