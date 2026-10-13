/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import pramaan.core.policy.BundleManifest;
import pramaan.core.policy.OperatingPointTable;

/**
 * {@code signBundle}: signs a liveness bundle — policy, operating-point table and exactly the table's models — the way
 * the server would before pushing it to clients (Bible Part 17.2).
 *
 * <pre>./gradlew :harness:signBundle --args="--config config --models models --key bundle.p8.der --version 2
 *     [--kid bundle] [--out config/bundle.jws]"</pre>
 * The client verifies it with the matching public key ({@code runScaffold --args="--bundle-key bundle.pub.der"}) and
 * refuses a version older than the last one it ran. Exit code 0 on success, 3 on error.
 */
public final class SignBundle {

    private SignBundle() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        for (String r : new String[] {"--config", "--models", "--key", "--version"}) {
            if (!a.containsKey(r)) {
                System.err.println("missing " + r);
                return 3;
            }
        }
        try {
            Path config = Path.of(a.get("--config"));
            byte[] policy = Files.readAllBytes(config.resolve(BundleManifest.POLICY_FILE));
            byte[] table = Files.readAllBytes(config.resolve(BundleManifest.TABLE_FILE));
            Map<String, byte[]> models = new TreeMap<>();
            for (String name : OperatingPointTable.modelNames(table, BundleManifest.TABLE_FILE)) {
                models.put(name, Files.readAllBytes(Path.of(a.get("--models")).resolve(name)));
            }
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(a.get("--key")))));
            String jws = BundleManifest.sign(Long.parseLong(a.get("--version")), Clock.systemUTC().instant(), policy, table,
                    models, key, a.getOrDefault("--kid", "bundle"));
            Path out = Path.of(a.getOrDefault("--out", config.resolve("bundle.jws").toString()));
            Files.write(out, jws.getBytes(StandardCharsets.US_ASCII));
            System.out.println("signed bundle version " + a.get("--version") + " (" + (models.size() + 2) + " files) -> " + out);
            return 0;
        } catch (Exception e) {
            System.err.println("bundle could not be signed: " + e);
            return 3;
        }
    }
}
