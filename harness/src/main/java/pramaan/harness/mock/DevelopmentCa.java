/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A throwaway device CA and one leaf certificate it signed, made with the JDK's own {@code keytool} so that no key is
 * ever checked in. A development device signs with the leaf and an {@code x5c} header; a client that trusts the CA
 * certificate verifies it exactly as it would verify a MOSIP device. Test and development material only.
 */
public final class DevelopmentCa {

    private final Path caCertificate;
    private final PrivateKey leafKey;
    private final X509Certificate leafCertificate;

    private DevelopmentCa(Path caCertificate, PrivateKey leafKey, X509Certificate leafCertificate) {
        this.caCertificate = caCertificate;
        this.leafKey = leafKey;
        this.leafCertificate = leafCertificate;
    }

    /** The CA's certificate (DER) - what a client's {@code --device-ca} points at. */
    public Path caCertificate() {
        return caCertificate;
    }

    public PrivateKey leafKey() {
        return leafKey;
    }

    public X509Certificate leafCertificate() {
        return leafCertificate;
    }

    /** True if this JDK ships {@code keytool}. */
    public static boolean available() {
        return keytool() != null;
    }

    /** @param dir a directory the keystores are written into; the leaf key is returned, never written bare */
    public static DevelopmentCa create(Path dir) throws Exception {
        File keytool = keytool();
        if (keytool == null) {
            throw new IllegalStateException("this JDK has no keytool");
        }
        java.nio.file.Files.createDirectories(dir);
        Path caStore = dir.resolve("ca.p12");
        Path leafStore = dir.resolve("leaf.p12");
        Path caDer = dir.resolve("device-ca.cer");
        Path csr = dir.resolve("leaf.csr");
        Path signed = dir.resolve("leaf-signed.cer");
        for (Path stale : new Path[] {caStore, leafStore, caDer, csr, signed}) {
            java.nio.file.Files.deleteIfExists(stale);
        }
        run(keytool, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-keystore",
                caStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit", "-dname",
                "CN=PRAMAAN Development Device CA", "-ext", "BC=ca:true");
        run(keytool, "-exportcert", "-alias", "ca", "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass",
                "changeit", "-file", caDer.toString());
        run(keytool, "-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-keystore",
                leafStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit", "-dname",
                "CN=PRAMAAN Development Profile Device");
        run(keytool, "-certreq", "-alias", "leaf", "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass",
                "changeit", "-file", csr.toString());
        run(keytool, "-gencert", "-alias", "ca", "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass",
                "changeit", "-infile", csr.toString(), "-outfile", signed.toString(), "-validity", "365");
        run(keytool, "-importcert", "-alias", "ca", "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass",
                "changeit", "-file", caDer.toString(), "-noprompt");
        run(keytool, "-importcert", "-alias", "leaf", "-keystore", leafStore.toString(), "-storetype", "PKCS12", "-storepass",
                "changeit", "-file", signed.toString());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(leafStore.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        return new DevelopmentCa(caDer, (PrivateKey) ks.getKey("leaf", "changeit".toCharArray()),
                (X509Certificate) ks.getCertificate("leaf"));
    }

    private static File keytool() {
        String ext = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
        File f = new File(System.getProperty("java.home"), "bin/keytool" + ext);
        return f.isFile() ? f : null;
    }

    private static void run(File keytool, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(keytool.getAbsolutePath());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + out);
        }
    }
}
