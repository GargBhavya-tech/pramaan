/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.testing.TestClock;

/** Bible Part 41 and Build Map #100: the profile's session-key agreement. */
class StreamKeyAgreementTest {

    private static final String KID = "mock-device";
    private static final String TXN = "0123456789abcdef0123456789abcdef";
    private static KeyPair deviceSigning;
    private static KeyPair strangerSigning;
    private static JwsTrust trust;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        deviceSigning = g.generateKeyPair();
        strangerSigning = g.generateKeyPair();
        trust = JwsTrust.builder(TestClock.at("2026-10-14T10:00:00Z")).key(KID, deviceSigning.getPublic()).build();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** What a conforming device does with a client's key: answer, signed, and keep its own derived Ks. */
    private static final class Device {
        final byte[] sessionKey;
        final String answer;

        Device(KeyPair signer, String transactionId, byte[] clientPublic, String hashOfKeyAnswered,
                byte[] devicePublicAnnounced) throws Exception {
            KeyPair eph = StreamKeyAgreement.newEphemeral();
            byte[] devicePublic = eph.getPublic().getEncoded();
            sessionKey = StreamKeyAgreement.sessionKey(eph.getPrivate(), clientPublic,
                    StreamKeyAgreement.transcriptHash(transactionId, devicePublic, clientPublic));
            byte[] announced = devicePublicAnnounced == null ? devicePublic : devicePublicAnnounced;
            String body = "{\"transactionId\":\"" + transactionId + "\",\"deviceEphemeralKey\":\""
                    + Base64.getEncoder().encodeToString(announced) + "\",\"clientEphemeralKeyHash\":\""
                    + hashOfKeyAnswered + "\"}";
            answer = Jws.signRs256(body.getBytes(StandardCharsets.UTF_8), KID, signer.getPrivate());
        }
    }

    @Test
    void clientAndDeviceDeriveTheSameSessionKey() throws Exception {
        KeyPair client = StreamKeyAgreement.newEphemeral();
        byte[] pub = client.getPublic().getEncoded();
        Device device = new Device(deviceSigning, TXN, pub, StreamKeyAgreement.keyHash(pub), null);
        byte[] clientKey = StreamKeyAgreement.acceptDeviceAnswer(device.answer, trust, client, TXN);
        assertEquals(32, clientKey.length);
        assertArrayEquals(device.sessionKey, clientKey);
    }

    @Test
    void eachTransactionGetsADifferentKey() throws Exception {
        KeyPair a = StreamKeyAgreement.newEphemeral();
        KeyPair b = StreamKeyAgreement.newEphemeral();
        Device da = new Device(deviceSigning, TXN, a.getPublic().getEncoded(),
                StreamKeyAgreement.keyHash(a.getPublic().getEncoded()), null);
        Device db = new Device(deviceSigning, TXN, b.getPublic().getEncoded(),
                StreamKeyAgreement.keyHash(b.getPublic().getEncoded()), null);
        assertFalse(Arrays.equals(da.sessionKey, db.sessionKey));
    }

    @Test
    void anAnswerSignedByAnUntrustedKeyIsRefused() throws Exception {
        KeyPair client = StreamKeyAgreement.newEphemeral();
        byte[] pub = client.getPublic().getEncoded();
        Device impostor = new Device(strangerSigning, TXN, pub, StreamKeyAgreement.keyHash(pub), null);
        assertThrows(JwsVerificationException.class,
                () -> StreamKeyAgreement.acceptDeviceAnswer(impostor.answer, trust, client, TXN));
    }

    @Test
    void anAnswerForAnotherTransactionIsRefused() throws Exception {
        KeyPair client = StreamKeyAgreement.newEphemeral();
        byte[] pub = client.getPublic().getEncoded();
        Device device = new Device(deviceSigning, "ffffffffffffffffffffffffffffffff", pub,
                StreamKeyAgreement.keyHash(pub), null);
        JwsVerificationException e = assertThrows(JwsVerificationException.class,
                () -> StreamKeyAgreement.acceptDeviceAnswer(device.answer, trust, client, TXN));
        assertTrue(e.getMessage().contains("another transaction"), e.getMessage());
    }

    @Test
    void aManInTheMiddleWhoSwappedTheClientKeyIsDetected() throws Exception {
        KeyPair client = StreamKeyAgreement.newEphemeral();
        KeyPair mitm = StreamKeyAgreement.newEphemeral();
        byte[] mitmPub = mitm.getPublic().getEncoded();
        // The device answers the key it actually received - the attacker's - so its signed answer names that key.
        Device device = new Device(deviceSigning, TXN, mitmPub, StreamKeyAgreement.keyHash(mitmPub), null);
        JwsVerificationException e = assertThrows(JwsVerificationException.class,
                () -> StreamKeyAgreement.acceptDeviceAnswer(device.answer, trust, client, TXN));
        assertTrue(e.getMessage().contains("different client key"), e.getMessage());
    }

    @Test
    void aSubstitutedDeviceKeyYieldsADifferentKeyNotTheDevicesOwn() throws Exception {
        KeyPair client = StreamKeyAgreement.newEphemeral();
        byte[] pub = client.getPublic().getEncoded();
        byte[] otherDeviceKey = StreamKeyAgreement.newEphemeral().getPublic().getEncoded();
        // Even a properly signed answer cannot be re-pointed at another key without the device's signature, but a
        // device that announced a key it does not hold gives the client a key the device cannot reproduce.
        Device device = new Device(deviceSigning, TXN, pub, StreamKeyAgreement.keyHash(pub), otherDeviceKey);
        byte[] clientKey = StreamKeyAgreement.acceptDeviceAnswer(device.answer, trust, client, TXN);
        assertFalse(Arrays.equals(device.sessionKey, clientKey));
    }

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = unhex("000102030405060708090a0b0c");
        byte[] info = unhex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = StreamKeyAgreement.hkdfSha256(ikm, salt, info, 42);
        assertArrayEquals(unhex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865"), okm);
    }

    @Test
    void theTranscriptDependsOnEveryField() {
        byte[] d = {1, 2, 3};
        byte[] c = {4, 5, 6};
        byte[] base = StreamKeyAgreement.transcriptHash(TXN, d, c);
        assertFalse(Arrays.equals(base, StreamKeyAgreement.transcriptHash(TXN + "0", d, c)));
        assertFalse(Arrays.equals(base, StreamKeyAgreement.transcriptHash(TXN, c, d)));
        assertFalse(Arrays.equals(base, StreamKeyAgreement.transcriptHash(TXN, d, new byte[] {4, 5, 7})));
    }
}
