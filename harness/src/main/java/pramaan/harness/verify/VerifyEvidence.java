/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.CanonicalJson;
import pramaan.core.inference.OnnxModel;
import pramaan.harness.Models;

/**
 * {@code harness/verify}: the server re-check in one command (Build Map #62).
 *
 * <pre>
 * ./gradlew :harness:verifyEvidence --args="--evidence e.jws --capture c.jws --evidence-key ev.pub.der
 *     --device-key dev.pub.der --allow allow.json [--device-kid ID] [--evidence-kid ID]
 *     [--server-key server.p8.der] [--models models/]"
 * </pre>
 * {@code allow.json}: {"models": ["sha256", ...], "policies": ["sha256", ...], "serverRejectBelow": 0.2}.
 * Keys are DER: X.509 SubjectPublicKeyInfo for public keys, PKCS#8 for the server's private key. Key ids
 * default to "evidence" and "device". Prints the report as JSON. Exit code: 0 CONFIRMED, 1 ADJUDICATE,
 * 2 REJECT, 3 usage or input error.
 */
public final class VerifyEvidence {

    private VerifyEvidence() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        for (String required : new String[] {"--evidence", "--capture", "--evidence-key", "--allow"}) {
            if (!a.containsKey(required)) {
                err.println("missing " + required);
                return 3;
            }
        }
        if (!a.containsKey("--device-key") && !a.containsKey("--device-ca")) {
            err.println("missing --device-key (a pinned device key) or --device-ca (the device CA certificate)");
            return 3;
        }
        MiniFasnetCropScorer scorer = null;
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            PublicKey evidenceKey = rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(Path.of(a.get("--evidence-key")))));
            PrivateKey serverKey = a.containsKey("--server-key")
                    ? rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(a.get("--server-key"))))) : null;
            JsonNode allow = CanonicalJson.parse(Files.readAllBytes(Path.of(a.get("--allow"))));
            Set<String> models = new HashSet<>();
            allow.path("models").forEach(n -> models.add(n.asText()));
            Set<String> policies = new HashSet<>();
            allow.path("policies").forEach(n -> policies.add(n.asText()));
            if (a.containsKey("--models")) {
                Path dir = Path.of(a.get("--models"));
                scorer = new MiniFasnetCropScorer(OnnxModel.load(Files.readAllBytes(dir.resolve(Models.MINIFAS_V2))));
            }
            Clock clock = Clock.systemUTC();
            RecheckVerifier v = new RecheckVerifier(
                    JwsTrust.builder(clock).key(a.getOrDefault("--evidence-kid", "evidence"), evidenceKey).build(),
                    deviceTrust(a, clock), models, policies,
                    serverKey, scorer, allow.path("serverRejectBelow").asDouble(0.2));
            RecheckReport r = v.verify(read(a.get("--evidence")), read(a.get("--capture")));
            out.println(r.toJson().toPrettyString());
            return r.route() == RecheckReport.Route.CONFIRMED ? 0 : r.route() == RecheckReport.Route.ADJUDICATE ? 1 : 2;
        } catch (Exception e) {
            err.println("verification could not run: " + e);
            return 3;
        } finally {
            if (scorer != null) {
                try {
                    scorer.close();
                } catch (ai.onnxruntime.OrtException ignored) {
                    // Closing the model is best effort at exit.
                }
            }
        }
    }

    /**
     * Who signs captures: a device CA ({@code --device-ca cert}, for devices whose captures carry an x5c chain, as
     * MockMDS's do) or one pinned key ({@code --device-key der}, with {@code --device-kid}).
     */
    static JwsTrust deviceTrust(Map<String, String> a, Clock clock) throws java.io.IOException, java.security.GeneralSecurityException {
        if (a.containsKey("--device-ca")) {
            java.security.cert.X509Certificate ca = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory
                    .getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(Files.readAllBytes(Path.of(a.get("--device-ca")))));
            return JwsTrust.builder(clock).anchor(ca).build();
        }
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(Path.of(a.get("--device-key")))));
        return JwsTrust.builder(clock).key(a.getOrDefault("--device-kid", "device"), key).build();
    }

    private static String read(String path) throws java.io.IOException {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.US_ASCII).trim();
    }
}
