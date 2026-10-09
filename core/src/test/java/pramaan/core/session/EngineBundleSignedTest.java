/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.BundleManifest;
import pramaan.core.policy.RollbackGuard;
import pramaan.core.testing.TestClock;

/** Bible Part 17.2, 22; Build Map #85: signed-bundle rollback refusal, and liveness never running with a switch off. */
class EngineBundleSignedTest {

    static KeyPair server;
    static TestClock clock;
    static JwsTrust trust;
    static final byte[] POLICY = "policy-bytes".getBytes(StandardCharsets.UTF_8);
    static final byte[] TABLE = "table-bytes".getBytes(StandardCharsets.UTF_8);
    static final Map<String, byte[]> MODELS = new TreeMap<>(Map.of("m.onnx", "model-bytes".getBytes(StandardCharsets.UTF_8)));

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        server = g.generateKeyPair();
        clock = TestClock.at("2026-09-26T00:00:00Z");
        trust = JwsTrust.builder(clock).key("bundle", server.getPublic()).build();
    }

    @Test
    void anOlderSignedVersionThanTheGuardHasAlreadySeenIsRefusedBeforeAnyModelLoads(@TempDir Path dir) throws Exception {
        RollbackGuard guard = RollbackGuard.file(dir.resolve("bundle.version"));
        guard.record(5);
        String olderJws = BundleManifest.sign(4, Instant.now(), POLICY, TABLE, MODELS, server.getPrivate(), "bundle");
        EngineUnavailableException e = assertThrows(EngineUnavailableException.class,
                () -> EngineBundle.loadSigned(olderJws, trust, POLICY, TABLE, MODELS, guard));
        assertEquals(LivenessError.BUNDLE_INVALID, e.error());
        assertTrue(e.getMessage().contains("rollback") || e.getMessage().contains("older"), e.getMessage());
        assertEquals(5, guard.highestSeen(), "a refused bundle never advances the recorded version");
    }

    @Test
    void aVersionEqualToTheHighestSeenIsNotTreatedAsARollback(@TempDir Path dir) throws Exception {
        // Equal is not "older": a client re-running the same bundle after a restart must not be refused for that
        // reason. Model bytes here are not real ONNX, so loading fails past the rollback check — which is exactly
        // what this test needs to show: the rollback guard itself did not refuse it.
        RollbackGuard guard = RollbackGuard.file(dir.resolve("bundle.version"));
        guard.record(4);
        String sameJws = BundleManifest.sign(4, Instant.now(), POLICY, TABLE, MODELS, server.getPrivate(), "bundle");
        EngineUnavailableException e = assertThrows(EngineUnavailableException.class,
                () -> EngineBundle.loadSigned(sameJws, trust, POLICY, TABLE, MODELS, guard));
        assertFalse(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("rollback"), e.getMessage());
    }

    @Test
    void policyEnabledFalseYieldsNoEngineAtAll() throws Exception {
        byte[] disabled = new String(Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml")), StandardCharsets.UTF_8)
                .replaceFirst("  enabled: true", "  enabled: false").getBytes(StandardCharsets.UTF_8);
        EngineUnavailableException e = assertThrows(EngineUnavailableException.class,
                () -> EngineBundle.load(disabled, TABLE, Map.of()));
        assertEquals(LivenessError.BUNDLE_INVALID, e.error());
        assertTrue(e.getMessage().contains("disabled by policy"), e.getMessage());
    }
}
