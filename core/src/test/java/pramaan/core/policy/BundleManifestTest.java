/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.CanonicalJson;
import pramaan.core.testing.TestClock;

/** Bible Part 17.2; Build Map #85: the signed bundle manifest and anti-rollback. */
class BundleManifestTest {

    static KeyPair server;
    static KeyPair stranger;
    static TestClock clock;
    static JwsTrust trust;

    static final byte[] POLICY = "policy-bytes".getBytes(StandardCharsets.UTF_8);
    static final byte[] TABLE = "table-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        server = g.generateKeyPair();
        stranger = g.generateKeyPair();
        clock = TestClock.at("2026-09-26T00:00:00Z");
        trust = JwsTrust.builder(clock).key("bundle", server.getPublic()).build();
    }

    static Map<String, byte[]> models(int... seeds) {
        Map<String, byte[]> m = new TreeMap<>();
        for (int s : seeds) {
            m.put("model" + s + ".onnx", ("model-bytes-" + s).getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void aSignedManifestVerifiesAndCarriesItsVersionAndIssueTime() throws Exception {
        Instant issued = Instant.parse("2026-09-01T00:00:00Z");
        String jws = BundleManifest.sign(3, issued, POLICY, TABLE, models(1, 2), server.getPrivate(), "bundle");
        BundleManifest m = BundleManifest.verify(jws, trust, POLICY, TABLE, models(1, 2));
        assertEquals(3, m.bundleVersion());
        assertEquals(issued, m.issuedAt());
        assertEquals(4, m.files().size(), "policy + table + two models");
        assertEquals(OperatingPointTable.sha256Hex(POLICY), m.files().get(BundleManifest.POLICY_FILE));
        assertEquals(OperatingPointTable.sha256Hex(TABLE), m.files().get(BundleManifest.TABLE_FILE));
    }

    @Test
    void anUntrustedSignerIsRejected() throws Exception {
        String jws = BundleManifest.sign(1, Instant.now(), POLICY, TABLE, models(1), stranger.getPrivate(), "bundle");
        InvalidPolicyException e = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(jws, trust, POLICY, TABLE, models(1)));
        assertTrue(e.getMessage().contains("signature"), e.getMessage());
    }

    @Test
    void anAlteredModelIsRejected() throws Exception {
        String jws = BundleManifest.sign(1, Instant.now(), POLICY, TABLE, models(1), server.getPrivate(), "bundle");
        Map<String, byte[]> tampered = models(1);
        tampered.put("model1.onnx", "different-bytes-entirely".getBytes(StandardCharsets.UTF_8));
        InvalidPolicyException e = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(jws, trust, POLICY, TABLE, tampered));
        assertTrue(e.getMessage().contains("model1.onnx") && e.getMessage().contains("altered"), e.getMessage());
    }

    @Test
    void anAlteredPolicyOrTableIsRejected() throws Exception {
        String jws = BundleManifest.sign(1, Instant.now(), POLICY, TABLE, models(1), server.getPrivate(), "bundle");
        InvalidPolicyException e1 = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(jws, trust, "different-policy".getBytes(StandardCharsets.UTF_8), TABLE, models(1)));
        assertTrue(e1.getMessage().contains(BundleManifest.POLICY_FILE) && e1.getMessage().contains("altered"), e1.getMessage());
        InvalidPolicyException e2 = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(jws, trust, POLICY, "different-table".getBytes(StandardCharsets.UTF_8), models(1)));
        assertTrue(e2.getMessage().contains(BundleManifest.TABLE_FILE) && e2.getMessage().contains("altered"), e2.getMessage());
    }

    @Test
    void aModelMissingFromTheBundleBeingLoadedIsRejected() throws Exception {
        // Signed for model1+model2; only model1 is on disk to load.
        String jws = BundleManifest.sign(1, Instant.now(), POLICY, TABLE, models(1, 2), server.getPrivate(), "bundle");
        InvalidPolicyException e = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(jws, trust, POLICY, TABLE, models(1)));
        assertTrue(e.getMessage().contains("model2.onnx") && e.getMessage().contains("missing"), e.getMessage());
    }

    @Test
    void anExtraModelNotInTheSignedBundleIsRejected() throws Exception {
        // Signed for model1 only; model1+model2 are on disk.
        String jws = BundleManifest.sign(1, Instant.now(), POLICY, TABLE, models(1), server.getPrivate(), "bundle");
        InvalidPolicyException e = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(jws, trust, POLICY, TABLE, models(1, 2)));
        assertTrue(e.getMessage().contains("model2.onnx") && e.getMessage().contains("not in the signed bundle"), e.getMessage());
    }

    @Test
    void versionsStartAtOne() {
        assertThrows(IllegalArgumentException.class,
                () -> BundleManifest.sign(0, Instant.now(), POLICY, TABLE, models(1), server.getPrivate(), "bundle"));
    }

    @Test
    void wrongSchemaVersionOrUnparseableIssueTimeIsRejected() throws Exception {
        InvalidPolicyException schema = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(manifestJws(o -> o.put("schema", "pramaan.bundle/2")), trust, POLICY, TABLE, models(1)));
        assertTrue(schema.getMessage().contains("pramaan.bundle/1"), schema.getMessage());

        InvalidPolicyException noVersion = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(manifestJws(o -> o.remove("bundleVersion")), trust, POLICY, TABLE, models(1)));
        assertTrue(noVersion.getMessage().contains("version"), noVersion.getMessage());

        InvalidPolicyException badTime = assertThrows(InvalidPolicyException.class,
                () -> BundleManifest.verify(manifestJws(o -> o.put("issuedAt", "not-a-time")), trust, POLICY, TABLE, models(1)));
        assertTrue(badTime.getMessage().contains("issue time"), badTime.getMessage());
    }

    /** A hand-built (still correctly signed) manifest, for testing the fields {@link BundleManifest#sign} always sets right. */
    private static String manifestJws(java.util.function.Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("schema", BundleManifest.SCHEMA);
        o.put("bundleVersion", 1);
        o.put("issuedAt", Instant.now().toString());
        ObjectNode f = o.putObject("files");
        f.put(BundleManifest.POLICY_FILE, OperatingPointTable.sha256Hex(POLICY));
        f.put(BundleManifest.TABLE_FILE, OperatingPointTable.sha256Hex(TABLE));
        models(1).forEach((name, bytes) -> f.put(name, OperatingPointTable.sha256Hex(bytes)));
        mutate.accept(o);
        return Jws.signRs256(CanonicalJson.write(o), "bundle", server.getPrivate());
    }

    // ---------------------------------------------------------------- RollbackGuard
    @Test
    void theFileGuardNeverLowersItsRecordedVersion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        RollbackGuard guard = RollbackGuard.file(dir.resolve("nested").resolve("bundle.version"));
        assertEquals(0, guard.highestSeen());
        guard.record(5);
        assertEquals(5, guard.highestSeen());
        guard.record(3);
        assertEquals(5, guard.highestSeen(), "never lowers the stored value");
        guard.record(7);
        assertEquals(7, guard.highestSeen());
    }
}
