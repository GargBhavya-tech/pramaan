/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;

/**
 * {@code --device-ca} (Build Map #62): a device that signs with an x5c chain, not a pinned key, is trusted through
 * a CA certificate — the shape MockMDS and real SBI devices use. The chain (CA, and a leaf it signed) is generated
 * with the JDK's own {@code keytool} at test time; if this environment has no keytool, the test is skipped rather
 * than shipping a checked-in key.
 */
class DeviceCaTrustTest {

    private static File keytool;

    @BeforeAll
    static void findKeytool() {
        String ext = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? ".exe" : "";
        File f = new File(System.getProperty("java.home"), "bin/keytool" + ext);
        keytool = f.isFile() ? f : null;
    }

    private static void run(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(keytool.getAbsolutePath());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + out);
        }
    }

    /** A CA keystore, a leaf keystore with a certificate the CA signed, and the CA's own DER certificate. */
    private static final class Chain {
        Path caDer;
        PrivateKey leafKey;
        X509Certificate leafCert;
    }

    private static Chain buildChain(Path dir, boolean secondCa) throws Exception {
        Path caStore = dir.resolve((secondCa ? "ca2" : "ca") + ".p12");
        Path leafStore = dir.resolve((secondCa ? "leaf2" : "leaf") + ".p12");
        Path caDer = dir.resolve((secondCa ? "ca2" : "ca") + ".der");
        Path csr = dir.resolve((secondCa ? "leaf2" : "leaf") + ".csr");
        Path leafSigned = dir.resolve((secondCa ? "leaf2" : "leaf") + "-signed.der");
        run("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=Test Device CA " + secondCa, "-ext", "BC=ca:true");
        run("-exportcert", "-alias", "ca", "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", caDer.toString());
        run("-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=Test Device Leaf " + secondCa);
        run("-certreq", "-alias", "leaf", "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", csr.toString());
        run("-gencert", "-alias", "ca", "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-infile", csr.toString(), "-outfile", leafSigned.toString(), "-validity", "365");
        run("-importcert", "-alias", "ca", "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", caDer.toString(), "-noprompt");
        run("-importcert", "-alias", "leaf", "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", leafSigned.toString());

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(leafStore.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        Chain c = new Chain();
        c.caDer = caDer;
        c.leafKey = (PrivateKey) ks.getKey("leaf", "changeit".toCharArray());
        c.leafCert = (X509Certificate) ks.getCertificate("leaf");
        return c;
    }

    /** Signs a payload RS256 with an x5c header carrying only the leaf certificate (never the anchor). */
    private static String signWithChain(byte[] payload, PrivateKey key, X509Certificate leaf) throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode header = json.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.putArray("x5c").add(Base64.getEncoder().encodeToString(leaf.getEncoded()));
        String input = b64(json.writeValueAsBytes(header)) + "." + b64(payload);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signer.sign());
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void aDeviceThatSignsWithAnX5cChainIsTrustedThroughTheCaCertificate(@TempDir Path dir) throws Exception {
        assumeTrue(keytool != null, "no keytool in this JDK");
        Chain chain = buildChain(dir, false);
        String jws = signWithChain("hello".getBytes(StandardCharsets.UTF_8), chain.leafKey, chain.leafCert);

        JwsTrust trust = VerifyEvidence.deviceTrust(Map.of("--device-ca", chain.caDer.toString()), java.time.Clock.systemUTC());
        assertEquals("hello", new String(Jws.verify(jws, trust).payloadBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aLeafSignedByADifferentCaIsRejected(@TempDir Path dir) throws Exception {
        assumeTrue(keytool != null, "no keytool in this JDK");
        Chain trusted = buildChain(dir, false);
        Chain other = buildChain(dir, true);
        // Signed by the second CA's leaf, but the trust anchor is only the first CA.
        String jws = signWithChain("hello".getBytes(StandardCharsets.UTF_8), other.leafKey, other.leafCert);

        JwsTrust trust = VerifyEvidence.deviceTrust(Map.of("--device-ca", trusted.caDer.toString()), java.time.Clock.systemUTC());
        assertThrows(JwsVerificationException.class, () -> Jws.verify(jws, trust));
    }

    @Test
    void devicePickedByPinnedKeyStillWorksWhenNoCaIsGiven() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        java.security.KeyPair kp = g.generateKeyPair();
        Path pub = Files.createTempFile("device-", ".der");
        Files.write(pub, kp.getPublic().getEncoded());
        String jws = Jws.signRs256("hi".getBytes(StandardCharsets.UTF_8), "device", kp.getPrivate());
        JwsTrust trust = VerifyEvidence.deviceTrust(Map.of("--device-key", pub.toString(), "--device-kid", "device"),
                java.time.Clock.systemUTC());
        assertEquals("hi", new String(Jws.verify(jws, trust).payloadBytes(), StandardCharsets.UTF_8));
    }
}
