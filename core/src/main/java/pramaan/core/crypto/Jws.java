/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Compact JWS signing and verification, RS256 only (the algorithm MOSIP devices use).
 *
 * <p>Verification rejects every other {@code alg}, including {@code none}; requires either an
 * {@code x5c} chain that validates against a {@link JwsTrust} anchor or a pinned {@code kid}; and only
 * then checks the signature. Callers must not read the payload of anything that did not pass.
 */
public final class Jws {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Jws() {
    }

    /** Signs a payload with RS256, identifying the key by {@code kid}. */
    public static String signRs256(byte[] payload, String kid, PrivateKey key) throws GeneralSecurityException {
        Objects.requireNonNull(payload, "payload");
        ObjectNode header = JSON.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", Objects.requireNonNull(kid, "kid"));
        String input;
        try {
            input = b64(JSON.writeValueAsBytes(header)) + "." + b64(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a JWS header", e);
        }
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(Objects.requireNonNull(key, "key"));
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signer.sign());
    }

    /**
     * Signs a payload with RS256 and an {@code x5c} header carrying only the signer's own certificate, the shape MOSIP
     * devices use: the verifier chains it to its anchor, which is never sent. For development devices and tests.
     */
    public static String signRs256(byte[] payload, X509Certificate signerCertificate, PrivateKey key)
            throws GeneralSecurityException {
        Objects.requireNonNull(payload, "payload");
        ObjectNode header = JSON.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.putArray("x5c").add(Base64.getEncoder().encodeToString(
                Objects.requireNonNull(signerCertificate, "signerCertificate").getEncoded()));
        String input;
        try {
            input = b64(JSON.writeValueAsBytes(header)) + "." + b64(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a JWS header", e);
        }
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(Objects.requireNonNull(key, "key"));
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signer.sign());
    }

    /** Verifies a compact JWS against the trust set; returns it only if it may be believed. */
    public static VerifiedJws verify(String compact, JwsTrust trust) throws JwsVerificationException {
        Objects.requireNonNull(trust, "trust");
        UnverifiedJws jws;
        JsonNode header;
        try {
            jws = UnverifiedJws.parse(compact);
            header = JSON.readTree(jws.headerJson());
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new JwsVerificationException("not a well-formed JWS: " + e.getMessage(), e);
        }

        String alg = header.path("alg").asText("");
        if (!"RS256".equals(alg)) {
            throw new JwsVerificationException("unsupported JWS algorithm '" + alg + "' (only RS256 is accepted)");
        }

        PublicKey key;
        String signer;
        JsonNode x5c = header.get("x5c");
        if (x5c != null && x5c.isArray() && x5c.size() > 0) {
            List<X509Certificate> chain = certificates(x5c);
            trust.validateChain(chain);
            key = chain.get(0).getPublicKey();
            signer = chain.get(0).getSubjectX500Principal().getName();
        } else if (header.hasNonNull("kid")) {
            String kid = header.get("kid").asText();
            key = trust.keyFor(kid);
            signer = "kid:" + kid;
        } else {
            throw new JwsVerificationException("JWS names no signer (neither x5c nor kid)");
        }

        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(jws.signingInput().getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(jws.signature())) {
                throw new JwsVerificationException("signature does not verify");
            }
        } catch (GeneralSecurityException e) {
            throw new JwsVerificationException("signature check failed: " + e.getMessage(), e);
        }
        return new VerifiedJws(jws, signer);
    }

    private static List<X509Certificate> certificates(JsonNode x5c) throws JwsVerificationException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (JsonNode encoded : x5c) {
                // x5c entries are standard base64 (not base64url), per RFC 7515 section 4.1.6.
                byte[] der = Base64.getMimeDecoder().decode(encoded.asText());
                chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
            return chain;
        } catch (CertificateException | IllegalArgumentException e) {
            throw new JwsVerificationException("x5c does not hold valid certificates: " + e.getMessage(), e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
