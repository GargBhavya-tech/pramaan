/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts sample crops to the server's key (Build Map #61): one fresh AES-256-GCM key per record, wrapped
 * with RSA-OAEP (SHA-256, MGF1-SHA-256); each crop gets its own 96-bit IV, and its index is authenticated
 * data so crops cannot be reordered. The SHA-256 of each plaintext is recorded beside it — inside the signed
 * evidence — so the server can prove the crops it decrypts are the ones the client signed.
 */
public final class SampleCrypto {

    public static final String ALG = "RSA-OAEP-256+A256GCM";
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT);

    private SampleCrypto() {
    }

    public static ObjectNode encrypt(List<byte[]> plaintexts, PublicKey server, SecureRandom random)
            throws GeneralSecurityException {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, random);
        SecretKey key = kg.generateKey();
        Cipher wrap = Cipher.getInstance("RSA/ECB/OAEPPadding");
        wrap.init(Cipher.ENCRYPT_MODE, server, OAEP, random);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("alg", ALG);
        out.put("wrappedKey", Base64.getEncoder().encodeToString(wrap.doFinal(key.getEncoded())));
        ArrayNode frames = out.putArray("frames");
        for (int i = 0; i < plaintexts.size(); i++) {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            gcm.updateAAD(Integer.toString(i).getBytes(StandardCharsets.US_ASCII));
            ObjectNode f = frames.addObject();
            f.put("sha256", sha256Hex(plaintexts.get(i)));
            f.put("iv", Base64.getEncoder().encodeToString(iv));
            f.put("ciphertext", Base64.getEncoder().encodeToString(gcm.doFinal(plaintexts.get(i))));
        }
        return out;
    }

    /** Decrypts every crop and checks it against its recorded hash; any mismatch throws. */
    public static List<byte[]> decrypt(JsonNode samples, PrivateKey server) throws GeneralSecurityException {
        if (!ALG.equals(samples.path("alg").asText())) {
            throw new GeneralSecurityException("unsupported sample encryption " + samples.path("alg").asText());
        }
        Cipher unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding");
        unwrap.init(Cipher.DECRYPT_MODE, server, OAEP);
        SecretKey key = new SecretKeySpec(unwrap.doFinal(Base64.getDecoder().decode(samples.path("wrappedKey").asText())), "AES");
        List<byte[]> out = new ArrayList<>();
        JsonNode frames = samples.path("frames");
        for (int i = 0; i < frames.size(); i++) {
            JsonNode f = frames.get(i);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(f.path("iv").asText())));
            gcm.updateAAD(Integer.toString(i).getBytes(StandardCharsets.US_ASCII));
            byte[] plain = gcm.doFinal(Base64.getDecoder().decode(f.path("ciphertext").asText()));
            if (!sha256Hex(plain).equals(f.path("sha256").asText())) {
                throw new GeneralSecurityException("sample " + i + " does not match its recorded hash");
            }
            out.add(plain);
        }
        return out;
    }

    static String sha256Hex(byte[] b) throws GeneralSecurityException {
        StringBuilder s = new StringBuilder(64);
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.toString();
    }
}
