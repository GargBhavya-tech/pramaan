/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.crypto.VerifiedJws;

/**
 * Session-key agreement for the SBI Liveness Profile's authenticated stream (Bible Part 41, point 2; Build Map #100).
 *
 * <p>The client sends an ephemeral P-256 public key with its nonce; the device answers with its own ephemeral key in
 * a JWS signed with its device key. The signed answer names the transaction and carries the hash of the client key it
 * answered, so a man in the middle who swapped the client's key is detected, and one who swapped the device's cannot
 * produce the signature. Both ends then derive {@code Ks} from the ECDH secret with HKDF-SHA256, salted with the
 * transcript hash:
 *
 * <pre>transcript = SHA-256(label || len||transactionId || len||devicePublic || len||clientPublic)
 * Ks = HKDF-SHA256(ECDH(own, peer), salt = transcript, info = label || "stream-key", L = 32)</pre>
 *
 * <p>P-256 rather than X25519 keeps this on every Android API level the engine targets.
 */
public final class StreamKeyAgreement {

    public static final String LABEL = "PRAMAAN-SBI-LP-0.1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private StreamKeyAgreement() {
    }

    /** A fresh ephemeral P-256 pair. */
    public static KeyPair newEphemeral() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 key generation is unavailable", e);
        }
    }

    /** SHA-256 of the client's encoded public key, in lower-case hex: how the device names the key it answered. */
    public static String keyHash(byte[] encodedPublicKey) {
        return hex(sha256(encodedPublicKey));
    }

    public static byte[] transcriptHash(String transactionId, byte[] devicePublic, byte[] clientPublic) {
        byte[] id = transactionId.getBytes(StandardCharsets.UTF_8);
        byte[] label = LABEL.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(label.length + 12 + id.length + devicePublic.length + clientPublic.length);
        b.put(label).putInt(id.length).put(id).putInt(devicePublic.length).put(devicePublic)
                .putInt(clientPublic.length).put(clientPublic);
        return sha256(b.array());
    }

    /** {@code Ks}: the ECDH secret of {@code own} and {@code peerPublic}, run through HKDF with the transcript. */
    public static byte[] sessionKey(PrivateKey own, byte[] peerPublic, byte[] transcriptHash) {
        try {
            PublicKey peer = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peerPublic));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(own);
            ka.doPhase(peer, true);
            byte[] info = (LABEL + "stream-key").getBytes(StandardCharsets.UTF_8);
            return hkdfSha256(ka.generateSecret(), transcriptHash, info, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot agree a session key: " + e.getMessage(), e);
        }
    }

    /**
     * The client's side: verify the device's signed answer and derive {@code Ks}.
     *
     * @throws JwsVerificationException if the answer is unsigned by a trusted device, names another transaction,
     *     or answers a key other than the client's own
     */
    public static byte[] acceptDeviceAnswer(String signedAnswer, JwsTrust trust, KeyPair client, String transactionId)
            throws JwsVerificationException {
        VerifiedJws jws = Jws.verify(signedAnswer, trust);
        JsonNode body;
        try {
            body = JSON.readTree(jws.payloadText());
        } catch (IOException e) {
            throw new JwsVerificationException("the device's answer is not JSON: " + e.getMessage(), e);
        }
        byte[] clientPublic = client.getPublic().getEncoded();
        if (!transactionId.equals(body.path("transactionId").asText())) {
            throw new JwsVerificationException("the device's answer is for another transaction");
        }
        if (!keyHash(clientPublic).equals(body.path("clientEphemeralKeyHash").asText())) {
            throw new JwsVerificationException("the device answered a different client key");
        }
        byte[] devicePublic;
        try {
            devicePublic = Base64.getDecoder().decode(body.path("deviceEphemeralKey").asText());
        } catch (IllegalArgumentException e) {
            throw new JwsVerificationException("the device's ephemeral key is not base64", e);
        }
        return sessionKey(client.getPrivate(), devicePublic, transcriptHash(transactionId, devicePublic, clientPublic));
    }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            for (int i = 1; pos < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            Arrays.fill(prk, (byte) 0);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
