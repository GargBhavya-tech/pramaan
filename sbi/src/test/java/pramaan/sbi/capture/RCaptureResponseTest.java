/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi.capture;

import pramaan.core.crypto.UnverifiedJws;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Parser behaviour on a hand-built response; the real MockMDS fixture is covered by RealFixtureTest. */
class RCaptureResponseTest {

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String jws(String payloadJson) {
        return b64("{\"alg\":\"RS256\"}") + "." + b64(payloadJson) + "." + b64("not-a-real-signature");
    }

    @Test
    void readsSignedFieldsFromThePayloadAndTheHashFromBesideIt() throws IOException {
        String payload = "{\"transactionId\":\"0f1e2d3c4b5a69788796a5b4c3d2e1f0\",\"timestamp\":\"2026-09-22T10:15:04Z\","
                + "\"bioType\":\"Face\",\"purpose\":\"Registration\",\"qualityScore\":\"80\",\"bioValue\":\"AAAA\"}";
        String response = "{\"biometrics\":[{\"specVersion\":\"0.9.5\",\"data\":\"" + jws(payload)
                + "\",\"hash\":\"ABCDEF\"}]}";

        RCaptureResponse parsed = RCaptureResponse.parse(response.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.entries().size());
        RCaptureResponse.Entry entry = parsed.entries().get(0);
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0", entry.unverifiedTransactionId());
        assertEquals("Face", entry.unverifiedBioType());
        assertEquals("ABCDEF", entry.hash());
        assertFalse(entry.payloadHas("hash"), "hash must not be inside the signed payload");
        assertTrue(entry.data().signingInput().endsWith(b64(payload)));
    }

    @Test
    void rejectsMalformedResponses() {
        assertThrows(IOException.class, () -> RCaptureResponse.parse("{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> UnverifiedJws.parse("only.two"));
        assertThrows(IllegalArgumentException.class, () -> UnverifiedJws.parse("a.b@d.c"));
    }
}
