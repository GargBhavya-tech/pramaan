/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.CanonicalJson;
import pramaan.core.inference.OnnxModel;

/**
 * {@code rescanEvidence}: the retroactive rescan in one command (Build Map #63).
 *
 * <pre>
 * ./gradlew :harness:rescanEvidence --args="--dir stored/ --model newer.onnx --server-key server.p8.der
 *     --evidence-key ev.pub.der --device-key dev.pub.der --allow allow.json [--device-kid ID] [--evidence-kid ID]"
 * </pre>
 * {@code --model} is the newer passive model (MiniFASNet V2 input/output contract). Prints the review list;
 * exit code 0 if nothing needs review, 1 if something does, 3 on a usage or input error.
 */
public final class RescanEvidence {

    private RescanEvidence() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        if (!a.containsKey("--device-key") && !a.containsKey("--device-ca")) {
            System.err.println("missing --device-key or --device-ca");
            return 3;
        }
        for (String required : new String[] {"--dir", "--model", "--server-key", "--evidence-key", "--allow"}) {
            if (!a.containsKey(required)) {
                System.err.println("missing " + required);
                return 3;
            }
        }
        try (MiniFasnetCropScorer scorer = new MiniFasnetCropScorer(OnnxModel.load(Files.readAllBytes(Path.of(a.get("--model")))))) {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            PublicKey ev = rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(Path.of(a.get("--evidence-key")))));
            PrivateKey server = rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(a.get("--server-key")))));
            JsonNode allow = CanonicalJson.parse(Files.readAllBytes(Path.of(a.get("--allow"))));
            Set<String> models = new HashSet<>();
            allow.path("models").forEach(n -> models.add(n.asText()));
            Set<String> policies = new HashSet<>();
            allow.path("policies").forEach(n -> policies.add(n.asText()));
            Clock clock = Clock.systemUTC();
            RecheckVerifier v = new RecheckVerifier(JwsTrust.builder(clock).key(a.getOrDefault("--evidence-kid", "evidence"), ev).build(),
                    VerifyEvidence.deviceTrust(a, clock), models, policies, server,
                    scorer, allow.path("serverRejectBelow").asDouble(0.2));
            List<RetroactiveRescan.Result> results = RetroactiveRescan.scan(Path.of(a.get("--dir")), v);
            JsonNode review = RetroactiveRescan.toReviewList(results);
            System.out.println(review.toPrettyString());
            return review.path("toReview").size() == 0 ? 0 : 1;
        } catch (Exception e) {
            System.err.println("rescan could not run: " + e);
            return 3;
        }
    }
}
