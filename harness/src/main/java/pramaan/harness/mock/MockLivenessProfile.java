/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import pramaan.core.binding.StreamKeyAgreement;
import pramaan.core.binding.StreamTagChain;
import pramaan.core.crypto.Jws;

/**
 * The device side of the SBI Liveness Profile, version 0.1 (Bible Part 41; Build Map #100): a reference
 * implementation for vendors, in the mock device. Everything is optional and backward compatible — a client that
 * does not know the profile never calls into it.
 *
 * <ol>
 *   <li>{@link #info()} — the {@code livenessProfile} block for the device-info response (capability flags).
 *   <li>{@link #answerKeyAgreement} — the device's signed answer to the client's ephemeral key.
 *   <li>{@link #tag} — one authenticated tag per preview frame, chained from the nonce.
 *   <li>{@link #checkpoint} — a signed head, to be emitted every N frames.
 *   <li>{@link #captureExtras} — the {@code streamTagHead} (and optional on-device PAD result) that belong inside the
 *       signed capture data, binding the capture to the stream.
 *   <li>{@link #emissionLog} — the signed log of what the device's illuminator actually showed and when.
 * </ol>
 *
 * <p>One instance serves one transaction. Test material only: the keys are whatever the caller passes in.
 */
public final class MockLivenessProfile {

    public static final String VERSION = "0.1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How the device signs what it sends. */
    public interface Signer {
        String sign(byte[] payload) throws GeneralSecurityException;
    }

    private final Signer signer;
    private String transactionId;
    private StreamTagChain chain;

    /** @param deviceKey the device's signing key, whose public half the client trusts under {@code kid} */
    public MockLivenessProfile(PrivateKey deviceKey, String kid) {
        this(payload -> Jws.signRs256(payload, kid, deviceKey));
    }

    /** @param signer signs with whatever the device's trust expects, for example an x5c chain */
    public MockLivenessProfile(Signer signer) {
        this.signer = signer;
    }

    /** The {@code livenessProfile} object of the device-info response. */
    public String info() {
        ObjectNode p = JSON.createObjectNode();
        p.put("version", VERSION);
        p.put("signedStream", true);
        p.put("captureBurst", true);
        ArrayNode illum = p.putArray("illuminatorControl");
        illum.add("VISIBLE_WHITE");
        p.put("sensorTimestamps", true);
        p.put("depth", false);
        p.put("focusControl", false);
        ObjectNode pad = p.putObject("onDevicePad");
        pad.put("algorithm", "mock-pad");
        pad.put("version", "0.1");
        pad.put("certification", "none");
        ObjectNode root = JSON.createObjectNode();
        root.set("livenessProfile", p);
        return write(root);
    }

    /**
     * Agrees {@code Ks} with the client and returns the signed answer to send back.
     *
     * @param clientPublic the client's encoded ephemeral public key from the request
     */
    public String answerKeyAgreement(String transactionId, byte[] clientPublic) {
        KeyPair mine = StreamKeyAgreement.newEphemeral();
        byte[] devicePublic = mine.getPublic().getEncoded();
        byte[] transcript = StreamKeyAgreement.transcriptHash(transactionId, devicePublic, clientPublic);
        byte[] sessionKey = StreamKeyAgreement.sessionKey(mine.getPrivate(), clientPublic, transcript);
        this.transactionId = transactionId;
        this.chain = new StreamTagChain(sessionKey, transactionId.getBytes(StandardCharsets.UTF_8));
        ObjectNode answer = JSON.createObjectNode();
        answer.put("version", VERSION);
        answer.put("transactionId", transactionId);
        answer.put("deviceEphemeralKey", Base64.getEncoder().encodeToString(devicePublic));
        answer.put("clientEphemeralKeyHash", StreamKeyAgreement.keyHash(clientPublic));
        return sign(write(answer));
    }

    /** The tag for the next preview frame. */
    public byte[] tag(byte[] frame, long timestampMillis) {
        return requireChain().next(frame, timestampMillis);
    }

    /** A signed {@code "<frame index>:<tag hex>"} over the current head. */
    public String checkpoint() {
        StreamTagChain c = requireChain();
        return sign(c.frames() + ":" + hex(c.head()));
    }

    /** The current head, as it would be put in the capture. */
    public byte[] streamTagHead() {
        return requireChain().head();
    }

    /** Fields the device adds to its signed capture data. */
    public String captureExtras(double onDevicePadScore) {
        ObjectNode n = JSON.createObjectNode();
        n.put("streamTagHead", hex(requireChain().head()));
        ObjectNode pad = n.putObject("onDevicePad");
        pad.put("algorithm", "mock-pad");
        pad.put("version", "0.1");
        pad.put("score", Double.toString(onDevicePadScore));
        return write(n);
    }

    /** One step of an illumination schedule, as the device actually performed it. */
    public static final class Emission {
        final String colour;
        final long atMillis;
        final long durationMillis;

        public Emission(String colour, long atMillis, long durationMillis) {
            this.colour = colour;
            this.atMillis = atMillis;
            this.durationMillis = durationMillis;
        }
    }

    /** The signed emission log for an {@code illuminate(schedule)} call. */
    public String emissionLog(List<Emission> performed) {
        requireChain();
        ObjectNode log = JSON.createObjectNode();
        log.put("version", VERSION);
        log.put("transactionId", transactionId);
        ArrayNode list = log.putArray("emissions");
        for (Emission e : performed) {
            ObjectNode n = list.addObject();
            n.put("colour", e.colour);
            n.put("atMs", Long.toString(e.atMillis)); // SBI style: every scalar is a string
            n.put("durationMs", Long.toString(e.durationMillis));
        }
        return sign(write(log));
    }

    private StreamTagChain requireChain() {
        if (chain == null) {
            throw new IllegalStateException("no key agreement yet: call answerKeyAgreement first");
        }
        return chain;
    }

    private String sign(String payload) {
        try {
            return signer.sign(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the mock device cannot sign: " + e.getMessage(), e);
        }
    }

    private static String write(Object node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
