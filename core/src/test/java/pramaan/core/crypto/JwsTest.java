/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.testing.TestClock;

class JwsTest {

    private static KeyPair device;
    private static KeyPair stranger;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        device = gen.generateKeyPair();
        stranger = gen.generateKeyPair();
    }

    private static JwsTrust trustDevice() {
        return JwsTrust.builder(TestClock.at("2026-09-23T10:00:00Z")).key("dev", device.getPublic()).build();
    }

    @Test
    void aSignedPayloadVerifiesAndCanThenBeRead() throws Exception {
        String jws = Jws.signRs256("{\"transactionId\":\"abcd\"}".getBytes(StandardCharsets.UTF_8), "dev", device.getPrivate());
        VerifiedJws verified = Jws.verify(jws, trustDevice());
        assertEquals("{\"transactionId\":\"abcd\"}", verified.payloadText());
        assertEquals("kid:dev", verified.signer());
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String jws = Jws.signRs256("{\"transactionId\":\"abcd\"}".getBytes(StandardCharsets.UTF_8), "dev", device.getPrivate());
        String[] parts = jws.split("\\.");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"transactionId\":\"evil\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        JwsVerificationException e = assertThrows(JwsVerificationException.class, () -> Jws.verify(forged, trustDevice()));
        assertTrue(e.getMessage().contains("does not verify"), e.getMessage());
    }

    @Test
    void aStrangersSignatureUnderATrustedKidIsRejected() throws Exception {
        String jws = Jws.signRs256("{}".getBytes(StandardCharsets.UTF_8), "dev", stranger.getPrivate());
        assertThrows(JwsVerificationException.class, () -> Jws.verify(jws, trustDevice()));
    }

    @Test
    void algNoneAndUnknownSignersAreRejected() throws Exception {
        String none = b64("{\"alg\":\"none\",\"kid\":\"dev\"}") + "." + b64("{}") + ".";
        assertTrue(assertThrows(JwsVerificationException.class, () -> Jws.verify(none, trustDevice()))
                .getMessage().contains("unsupported JWS algorithm"));

        String unknownKid = Jws.signRs256("{}".getBytes(StandardCharsets.UTF_8), "someone", device.getPrivate());
        assertThrows(JwsVerificationException.class, () -> Jws.verify(unknownKid, trustDevice()));

        JwsTrust empty = JwsTrust.builder(TestClock.at("2026-09-23T10:00:00Z")).build();
        assertThrows(JwsVerificationException.class, () -> Jws.verify(unknownKid, empty));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
