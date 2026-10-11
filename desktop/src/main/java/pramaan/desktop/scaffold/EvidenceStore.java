/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import pramaan.core.binding.VerifiedCapture;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.evidence.CbeffOthers;
import pramaan.core.evidence.EvidencePackager;
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.session.AttemptRecord;
import pramaan.core.session.EngineBundle;
import pramaan.core.transparency.AttemptEntry;
import pramaan.core.transparency.MerkleLog;
import pramaan.core.transparency.SignedTreeHead;
import pramaan.desktop.ImageIoJpegEncoder;

/**
 * Where the scaffold keeps what a Registration Client would put in the packet and sync (ADR 0003 patch P4; Build
 * Map #61, #64): for each accepted resident capture, a CBEFF packet whose face BIR carries the signed evidence under
 * {@code PRAMAAN_LIVENESS}, plus the evidence and capture as separate files, in the layout {@code rescanEvidence}
 * reads; and a transparency log of every attempt, pass or fail, with a signed tree head after each.
 *
 * <p>The keys are development keys made on first run under {@code build/} (gitignored): the evidence key stands in
 * for the client machine key (Bible Q9), the server key for the Registration Processor's. {@code allow.json} lists
 * this bundle's model and policy hashes, so {@code verifyEvidence} can re-check a packet in one command.
 */
final class EvidenceStore {

    static final String EVIDENCE_KID = "evidence";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final Path packets;
    private final EvidencePackager packager;
    private final JwsTrust deviceTrust;
    private final MerkleLog log;
    private final PrivateKey treeHeadKey;
    private final Clock clock;
    private AttemptRecord pendingAccepted;

    EvidenceStore(Path dir, EngineBundle bundle, JwsTrust deviceTrust, SecureRandom random, Clock clock)
            throws IOException, GeneralSecurityException {
        this.dir = dir;
        this.packets = dir.resolve("packets");
        this.deviceTrust = deviceTrust;
        this.clock = clock;
        Files.createDirectories(packets);
        KeyPair evidence = keys(dir.resolve("keys"), "evidence", random);
        KeyPair server = keys(dir.resolve("keys"), "server", random);
        this.treeHeadKey = evidence.getPrivate();
        this.packager = new EvidencePackager(new EvidenceSigner(evidence.getPrivate(), EVIDENCE_KID), server.getPublic(),
                new ImageIoJpegEncoder(), random);
        this.log = MerkleLog.open(dir.resolve("attempts.log"));
        writeAllowList(bundle);
    }

    /** Every attempt as it ends. Accepted ones wait for their evidence, so the leaf can name it. */
    synchronized void attemptEnded(AttemptRecord r) {
        if (AttemptRecord.ACCEPTED.equals(r.outcome())) {
            pendingAccepted = r;
            return;
        }
        append(AttemptEntry.of(r, null));
    }

    /**
     * Signs and stores the evidence of an accepted resident capture, embeds it in the packet's face BIR, and logs
     * the attempt with the evidence hash. Returns the packet file.
     */
    synchronized Path storeAccepted(LivenessEngine engine, String operatorId, String centreId)
            throws IOException, GeneralSecurityException {
        EvidencePackager.Packet p = packager.pack(engine, operatorId, centreId, clock.instant());
        String id = engine.session().sessionId() + "-a" + engine.session().attempt();
        byte[] iso;
        try {
            iso = VerifiedCapture.verify(p.capture(), deviceTrust).isoRecord();
        } catch (pramaan.core.crypto.JwsVerificationException e) {
            throw new GeneralSecurityException("the accepted capture no longer verifies: " + e.getMessage(), e);
        }
        byte[] packet = CbeffOthers.embed(cbeff(iso), "Face", CbeffOthers.KEY, p.evidenceJws());
        Path file = packets.resolve(id + ".cbeff.xml");
        Files.write(file, packet);
        Files.writeString(packets.resolve(id + ".evidence.jws"), p.evidenceJws());
        Files.writeString(packets.resolve(id + ".capture.jws"), p.capture().signedData());
        AttemptRecord r = pendingAccepted;
        pendingAccepted = null;
        append(r == null ? AttemptEntry.of(engine.session(), p.record()) : AttemptEntry.of(r, p.record()));
        return file;
    }

    /** Entries in the transparency log so far. */
    synchronized long logSize() {
        return log.size();
    }

    private void append(byte[] entry) {
        try {
            log.append(entry);
            long counter = nextCounter();
            Files.writeString(dir.resolve("latest.sth.jws"),
                    SignedTreeHead.sign(log, clock.instant(), counter, treeHeadKey, EVIDENCE_KID).jws());
        } catch (IOException | GeneralSecurityException e) {
            // The attempt still happened; a log that cannot be written is reported, never silently skipped.
            System.err.println("PRAMAAN: transparency log write failed: " + e.getMessage());
        }
    }

    /** The tree-head counter only goes up, even if the log file is replaced (Bible Part 42). */
    private long nextCounter() throws IOException {
        Path f = dir.resolve("sth.counter");
        long next = (Files.exists(f) ? Long.parseLong(Files.readString(f).trim()) : 0) + 1;
        Files.writeString(f, Long.toString(next));
        return next;
    }

    /** A minimal CBEFF with one face BIR, as MOSIP's cbeff.xsd shapes it; the BDB is the signed ISO record. */
    private static byte[] cbeff(byte[] iso) {
        String bdb = Base64.getUrlEncoder().withoutPadding().encodeToString(iso);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<BIR xmlns=\"http://standards.iso.org/iso-iec/19785/-3/ed-2/\"><BIRInfo><Integrity>false</Integrity></BIRInfo>"
                + "<BIR><BIRInfo><Integrity>false</Integrity></BIRInfo><BDBInfo><Format><Organization>Mosip</Organization>"
                + "<Type>8</Type></Format><Type>Face</Type><Purpose>Enroll</Purpose></BDBInfo><BDB>" + bdb + "</BDB></BIR></BIR>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private void writeAllowList(EngineBundle bundle) throws IOException {
        ObjectNode allow = JSON.createObjectNode();
        ArrayNode models = allow.putArray("models");
        bundle.table().models().values().forEach(models::add);
        allow.putArray("policies").add(bundle.policySha256());
        allow.put("serverRejectBelow", 0.2);
        Files.writeString(dir.resolve("allow.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(allow));
    }

    /** A development RSA pair, made once: {@code <name>.p8.der} (PKCS#8) and {@code <name>.pub.der} (X.509). */
    private static KeyPair keys(Path dir, String name, SecureRandom random) throws IOException, GeneralSecurityException {
        Files.createDirectories(dir);
        Path priv = dir.resolve(name + ".p8.der");
        Path pub = dir.resolve(name + ".pub.der");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (Files.exists(priv) && Files.exists(pub)) {
            PrivateKey k = rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv)));
            PublicKey p = rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub)));
            return new KeyPair(p, k);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, random);
        KeyPair kp = g.generateKeyPair();
        Files.write(priv, kp.getPrivate().getEncoded());
        Files.write(pub, kp.getPublic().getEncoded());
        return kp;
    }
}
