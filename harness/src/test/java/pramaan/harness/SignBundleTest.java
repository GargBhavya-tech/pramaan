/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.BundleManifest;

/** Bible Part 17.2; Build Map #85: the {@code signBundle} CLI. */
class SignBundleTest {

    /** A schema-valid operating-point table (Part 9 shape) bound to one fake model. */
    private static String table(String modelHash) {
        return "{\"tableVersion\":1,\"provisional\":true,\"generatedBy\":\"test\","
                + "\"models\":{\"m1.onnx\":\"" + modelHash + "\"},"
                + "\"fusion\":{\"cues\":{\"passive\":{\"wPos\":1.0,\"wNeg\":1.0,\"physiological\":false}},"
                + "\"bias\":{\"L0\":-0.5,\"L1\":0.0},\"disagreementLogitGap\":4.0},"
                + "\"binding\":{\"thetaSame\":0.363},"
                + "\"deviceClasses\":{\"default\":{\"resident-default\":{\"tauAccept\":0.85,\"tauReject\":0.20,\"target\":\"t\"}}}}";
    }

    @Test
    void signsExactlyThePolicyTableAndTheTablesModels(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path models = Files.createDirectory(dir.resolve("models"));
        Files.writeString(config.resolve(BundleManifest.POLICY_FILE), "policy: contents\n");
        byte[] model1 = "model-one".getBytes(StandardCharsets.UTF_8);
        Files.write(models.resolve("m1.onnx"), model1);
        Files.writeString(config.resolve(BundleManifest.TABLE_FILE),
                table(pramaan.core.policy.OperatingPointTable.sha256Hex(model1)));

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Path key = dir.resolve("bundle.p8.der");
        Files.write(key, kp.getPrivate().getEncoded());
        Path out = dir.resolve("bundle.jws");

        int code = SignBundle.run(new String[] {"--config", config.toString(), "--models", models.toString(), "--key",
            key.toString(), "--version", "3", "--out", out.toString()});
        assertEquals(0, code);
        String jws = Files.readString(out).trim();

        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(kp.getPublic().getEncoded()));
        JwsTrust trust = JwsTrust.builder(Clock.systemUTC()).key("bundle", pub).build();
        BundleManifest m = BundleManifest.verify(jws, trust, Files.readAllBytes(config.resolve(BundleManifest.POLICY_FILE)),
                Files.readAllBytes(config.resolve(BundleManifest.TABLE_FILE)), Map.of("m1.onnx", model1));
        assertEquals(3, m.bundleVersion());
        assertEquals(3, m.files().size(), "policy + table + one model");
    }

    @Test
    void missingArgumentsExitThree() {
        assertEquals(3, SignBundle.run(new String[] {"--config", "x"}));
    }

    @Test
    void anAlteredModelAfterSigningFailsVerification(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path models = Files.createDirectory(dir.resolve("models"));
        Files.writeString(config.resolve(BundleManifest.POLICY_FILE), "policy: contents\n");
        byte[] model1 = "model-one".getBytes(StandardCharsets.UTF_8);
        Files.write(models.resolve("m1.onnx"), model1);
        Files.writeString(config.resolve(BundleManifest.TABLE_FILE),
                table(pramaan.core.policy.OperatingPointTable.sha256Hex(model1)));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Path key = dir.resolve("bundle.p8.der");
        Files.write(key, kp.getPrivate().getEncoded());
        Path out = dir.resolve("bundle.jws");
        assertEquals(0, SignBundle.run(new String[] {"--config", config.toString(), "--models", models.toString(), "--key",
            key.toString(), "--version", "1", "--out", out.toString()}));

        byte[] tampered = "model-one-but-different".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(pramaan.core.policy.OperatingPointTable.sha256Hex(model1),
                pramaan.core.policy.OperatingPointTable.sha256Hex(tampered));
        JwsTrust trust = JwsTrust.builder(Clock.systemUTC())
                .key("bundle", KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(kp.getPublic().getEncoded()))).build();
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(pramaan.core.policy.InvalidPolicyException.class,
                () -> BundleManifest.verify(Files.readString(out).trim(), trust,
                        Files.readAllBytes(config.resolve(BundleManifest.POLICY_FILE)),
                        Files.readAllBytes(config.resolve(BundleManifest.TABLE_FILE)), Map.of("m1.onnx", tampered)))
                .getMessage().contains("altered"));
    }
}
