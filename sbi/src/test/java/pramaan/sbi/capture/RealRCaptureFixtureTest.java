/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi.capture;

import pramaan.core.crypto.UnverifiedJws;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Build Map #4: the SBI contract, proven on a real response from MOSIP's MockMDS rather than assumed.
 *
 * <p>The fixture holds a face image, so it is never committed (see .gitignore and docs/test-data.md). Regenerate it with a running
 * MockMDS: {@code python tools/capture_sbi_fixtures.py}. Without it this test is skipped, not passed.
 */
class RealRCaptureFixtureTest {

    private static final Path TESTDATA = Path.of("testdata");
    private static final Path RCAPTURE = TESTDATA.resolve("rcapture-face.json");
    private static final Path META = TESTDATA.resolve("capture-meta.json");

    private RCaptureResponse.Entry entry;
    private String sentNonce;

    @BeforeEach
    void loadFixture() throws Exception {
        assumeTrue(Files.exists(RCAPTURE) && Files.exists(META),
                "real rcapture fixture absent; run MockMDS and `python tools/capture_sbi_fixtures.py`");
        JsonNode meta = new ObjectMapper().readTree(Files.readAllBytes(META));
        sentNonce = meta.path("nonce").asText();
        RCaptureResponse response = RCaptureResponse.parse(Files.readAllBytes(RCAPTURE));
        assertEquals(1, response.entries().size(), "one face requested, one entry expected");
        entry = response.entries().get(0);
    }

    @Test
    void theNonceWeSentComesBackInsideTheSignedPayload() {
        assertTrue(sentNonce.matches("[0-9a-f]{32}"), "nonce must be 32 lowercase hex characters");
        assertEquals(sentNonce, entry.unverifiedTransactionId(),
                "transactionId must be echoed inside the device-signed JWS; this is the replay lock (#54)");
    }

    @Test
    void signedPayloadCarriesTheFieldsBindingDependsOn() {
        for (String field : new String[] {"transactionId", "timestamp", "digitalId", "bioValue", "qualityScore",
                "purpose", "bioType"}) {
            assertTrue(entry.payloadHas(field), "expected '" + field + "' inside the signed payload");
        }
        assertEquals("Face", entry.unverifiedBioType());
        assertEquals("Registration", entry.unverifiedPurpose());
    }

    @Test
    void chainedHashTravelsBesideTheSignatureNotInsideIt() {
        assertFalse(entry.payloadHas("hash"), "hash must not be inside the signed payload");
        assertTrue(entry.hash() != null && entry.hash().matches("[0-9A-Fa-f]{64}"), "hash should be hex SHA-256");
    }

    /**
     * The chained hash, proven against a real MockMDS response (docs/sbi-notes.md). The reference
     * implementation hashes the decoded ISO biometric record and concatenates <em>raw</em> 32-byte
     * digests, not hex strings:
     *
     * <pre>hash = UPPERHEX(SHA256(prev || SHA256(base64url-decode(bioValue))))</pre>
     *
     * where {@code prev} is SHA-256 of the empty string for the first capture, else the hex-decoded
     * {@code previousHash} sent in the request. The verifier in #55 implements exactly this.
     */
    @Test
    void chainedHashIsSha256OfPreviousDigestAndDigestOfTheIsoRecord() throws Exception {
        String bioValue = new ObjectMapper()
                .readTree(entry.data().payloadText())
                .path("bioValue")
                .asText();
        byte[] iso = Base64.getUrlDecoder().decode(bioValue);
        assertEquals("FAC", new String(iso, 0, 3, StandardCharsets.US_ASCII), "bioValue should be an ISO 19794-5 face record");

        byte[] previous = sha256(new byte[0]);
        byte[] current = sha256(iso);
        byte[] chained = new byte[previous.length + current.length];
        System.arraycopy(previous, 0, chained, 0, previous.length);
        System.arraycopy(current, 0, chained, previous.length, current.length);

        assertEquals(hex(sha256(chained)), entry.hash().toUpperCase(Locale.ROOT));
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }
}
