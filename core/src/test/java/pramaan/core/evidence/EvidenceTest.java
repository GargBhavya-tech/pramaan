/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.image.RgbImage;
import pramaan.core.testing.TestClock;

/** Build Map #59-#61. */
class EvidenceTest {

    static KeyPair client;
    static KeyPair server;
    static JwsTrust trust;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        client = g.generateKeyPair();
        server = g.generateKeyPair();
        trust = JwsTrust.builder(TestClock.at("2026-09-23T10:00:00Z")).key("evidence", client.getPublic()).build();
    }

    static EvidenceRecord sample() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("schema", EvidenceRecord.SCHEMA);
        o.put("zeta", "last");
        o.put("sessionNonce", "0123456789abcdef0123456789abcdef");
        o.put("pLive", 0.62);
        o.put("third", 1.0 / 3);
        o.put("attempt", 2);
        o.putObject("cues").put("moire", 0.7).put("flatness", 0.93);
        o.putArray("list").add("\u00e9 \"quoted\" \\ \n");
        return EvidenceRecord.of(o);
    }

    // ---------------------------------------------------------------- #59
    @Test
    void canonicalFormIsStableAcrossARoundTrip() throws Exception {
        byte[] once = sample().canonical();
        String text = new String(once, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("{\"attempt\":2,\"cues\":{\"flatness\":0.93,\"moire\":0.7},"), text);
        assertTrue(text.contains("\"third\":0.333333"), text);
        assertArrayEquals(once, CanonicalJson.write(CanonicalJson.parse(once)), "serialise, parse, serialise: byte-identical");
        assertTrue(CanonicalJson.isCanonical(once));
        assertFalse(CanonicalJson.isCanonical(text.replace(",", ", ").getBytes(StandardCharsets.UTF_8)));
        ObjectNode bad = JsonNodeFactory.instance.objectNode();
        bad.put("x", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(bad));
    }

    @Test
    void flippingOneByteOfThePayloadFailsVerification() throws Exception {
        String jws = new EvidenceSigner(client.getPrivate(), "evidence").sign(sample());
        assertEquals("last", EvidenceSigner.verify(jws, trust).text("zeta"));
        String[] p = jws.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(p[1]);
        for (int i = 0; i < payload.length; i += 7) {
            byte[] flipped = payload.clone();
            flipped[i] ^= 1;
            String forged = p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(flipped) + "." + p[2];
            assertThrows(EvidenceException.class, () -> EvidenceSigner.verify(forged, trust), "byte " + i);
        }
    }

    @Test
    void aValidSignatureOverNonCanonicalJsonIsStillRefused() throws Exception {
        byte[] pretty = ("{\"schema\": \"" + EvidenceRecord.SCHEMA + "\"}").getBytes(StandardCharsets.UTF_8);
        String jws = Jws.signRs256(pretty, "evidence", client.getPrivate());
        assertThrows(EvidenceException.class, () -> EvidenceSigner.verify(jws, trust));
    }

    // ---------------------------------------------------------------- #60
    static String cbeff(String ns) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<BIR xmlns=\"" + ns + "\"><BIRInfo><Integrity>false</Integrity></BIRInfo>"
                + "<BIR><BIRInfo><Integrity>false</Integrity></BIRInfo><BDBInfo><Type>Finger</Type></BDBInfo>"
                + "<BDB>RklOR0VS</BDB></BIR>"
                + "<BIR><BIRInfo><Integrity>false</Integrity></BIRInfo><BDBInfo><Type>Face</Type></BDBInfo>"
                + "<BDB>RkFDRURBVEE=</BDB><Others><Key>EXCEPTION</Key><Value>false</Value></Others></BIR></BIR>";
    }

    static final String ISO_NS = "http://standards.iso.org/iso-iec/19785/-3/ed-2/";

    @Test
    void evidenceRidesInTheFaceBirsOthersWithoutTouchingTheBiometrics() throws Exception {
        byte[] before = cbeff(ISO_NS).getBytes(StandardCharsets.UTF_8);
        byte[] after = CbeffOthers.embed(before, "Face", CbeffOthers.KEY, "header.payload.signature");
        assertEquals("header.payload.signature", CbeffOthers.read(after, "Face", CbeffOthers.KEY).orElseThrow());
        assertEquals(CbeffOthers.bdb(before, "Face"), CbeffOthers.bdb(after, "Face"), "the face BDB is untouched");
        assertEquals(CbeffOthers.bdb(before, "Finger"), CbeffOthers.bdb(after, "Finger"));
        assertEquals("false", CbeffOthers.read(after, "Face", "EXCEPTION").orElseThrow(), "MOSIP's own flags stay");
        assertTrue(CbeffOthers.read(after, "Finger", CbeffOthers.KEY).isEmpty(), "only the face BIR carries it");
        byte[] again = CbeffOthers.embed(after, "Face", CbeffOthers.KEY, "second");
        assertEquals("second", CbeffOthers.read(again, "Face", CbeffOthers.KEY).orElseThrow());
        assertEquals(1, new String(again, StandardCharsets.UTF_8).split(CbeffOthers.KEY, -1).length - 1, "replaced, not duplicated");
    }

    @Test
    void aCbeffWithADoctypeIsRefusedSoNoExternalEntityIsEverRead() {
        byte[] xxe = ("<?xml version=\"1.0\"?><!DOCTYPE BIR [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<BIR><BIR><BDBInfo><Type>Face</Type></BDBInfo><BDB>&x;</BDB></BIR></BIR>").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> CbeffOthers.embed(xxe, "Face", CbeffOthers.KEY, "v"));
    }

    @Test
    void theResultStillValidatesAgainstMosipsOwnCbeffSchema() throws Exception {
        Path xsd = Path.of("..", "third_party", "mosip-cbeff", "schema", "cbeff.xsd");
        Assumptions.assumeTrue(Files.exists(xsd), "MOSIP cbeff.xsd absent (unzip it from kernel-cbeffutil-api 1.2.0.3)");
        Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd.toFile());
        byte[] before = cbeff("http://docs.oasis-open.org/bias/ns/biaspatronformat-1.0/").getBytes(StandardCharsets.UTF_8);
        schema.newValidator().validate(new StreamSource(new ByteArrayInputStream(before)));
        byte[] after = CbeffOthers.embed(before, "Face", CbeffOthers.KEY, "header.payload.signature");
        schema.newValidator().validate(new StreamSource(new ByteArrayInputStream(after)));
    }

    // ---------------------------------------------------------------- #61
    static RgbImage noise(int seed) {
        byte[] b = new byte[112 * 112 * 3];
        new SecureRandom(new byte[] {(byte) seed}).nextBytes(b);
        return RgbImage.of(112, 112, b);
    }

    @Test
    void samplesDecryptWithTheServerKeyAndMatchTheirRecordedHashes() throws Exception {
        List<byte[]> crops = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            crops.add(("jpeg-" + i).getBytes(StandardCharsets.US_ASCII));
        }
        ObjectNode enc = SampleCrypto.encrypt(crops, server.getPublic(), new SecureRandom());
        List<byte[]> back = SampleCrypto.decrypt(enc, server.getPrivate());
        for (int i = 0; i < crops.size(); i++) {
            assertArrayEquals(crops.get(i), back.get(i));
            assertEquals(SampleCrypto.sha256Hex(crops.get(i)), enc.get("frames").get(i).get("sha256").asText());
        }
        ObjectNode swapped = enc.deepCopy();
        ((ArrayNode) swapped.get("frames")).set(0, enc.get("frames").get(1).deepCopy());
        assertThrows(GeneralSecurityException.class, () -> SampleCrypto.decrypt(swapped, server.getPrivate()),
                "crops cannot be reordered");
        assertThrows(GeneralSecurityException.class, () -> SampleCrypto.decrypt(enc, client.getPrivate()),
                "only the server key opens them");
    }

    @Test
    void selectionTakesTheBestPassiveFrameAndOnePerStepBetweenThreeAndFive() {
        List<SampleSelector.Candidate> cs = new ArrayList<>();
        cs.add(new SampleSelector.Candidate(-1, 0.4, noise(1)));
        RgbImage bestPassive = noise(2);
        cs.add(new SampleSelector.Candidate(-1, 0.9, bestPassive));
        RgbImage step0 = noise(3);
        cs.add(new SampleSelector.Candidate(0, 0.7, step0));
        cs.add(new SampleSelector.Candidate(0, 0.2, noise(4)));
        List<RgbImage> picked = SampleSelector.select(cs);
        assertEquals(3, picked.size(), "topped up to the minimum");
        assertTrue(picked.contains(bestPassive) && picked.contains(step0));
        List<SampleSelector.Candidate> many = new ArrayList<>();
        for (int step = -1; step < 8; step++) {
            many.add(new SampleSelector.Candidate(step, 0.5, noise(10 + step)));
        }
        assertEquals(SampleSelector.MAX, SampleSelector.select(many).size(), "never more than five");
    }
}
