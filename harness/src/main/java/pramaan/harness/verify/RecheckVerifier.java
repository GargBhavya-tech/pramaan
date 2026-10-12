/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import pramaan.core.binding.SbiCaptureChain;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.CanonicalJson;
import pramaan.core.evidence.EvidenceException;
import pramaan.core.evidence.EvidenceRecord;
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.evidence.SampleCrypto;
import pramaan.core.image.RgbImage;

/**
 * The server re-check (Bible Part 14, {@code LivenessRecheckStage}; Build Map #62). Nothing here trusts the
 * client's word: signatures are verified, the capture is tied back to the evidence, hashes are compared with
 * an allow-list, and the sample crops are re-scored locally.
 *
 * <p>Findings route the packet: tampering REJECTs; anything a human should see (an unapproved model, a
 * client ACCEPT the server doubts) goes to ADJUDICATE. An unapproved model is a finding, never a crash.
 */
public final class RecheckVerifier {

    private final JwsTrust evidenceTrust;
    private final JwsTrust deviceTrust;
    private final Set<String> approvedModelHashes;
    private final Set<String> approvedPolicyHashes;
    private final PrivateKey serverKey;
    private final CropScorer scorer;
    private final double serverRejectBelow;

    /**
     * @param serverKey decrypts the sample crops; null skips re-scoring (reported as a finding)
     * @param scorer the server's model; null skips re-scoring (reported as a finding)
     * @param serverRejectBelow a client ACCEPT whose crops the server scores below this goes to adjudication
     */
    public RecheckVerifier(JwsTrust evidenceTrust, JwsTrust deviceTrust, Set<String> approvedModelHashes,
            Set<String> approvedPolicyHashes, PrivateKey serverKey, CropScorer scorer, double serverRejectBelow) {
        this.evidenceTrust = evidenceTrust;
        this.deviceTrust = deviceTrust;
        this.approvedModelHashes = approvedModelHashes;
        this.approvedPolicyHashes = approvedPolicyHashes;
        this.serverKey = serverKey;
        this.scorer = scorer;
        this.serverRejectBelow = serverRejectBelow;
    }

    /**
     * @param evidenceJws the signed evidence from the BIR's Others
     * @param captureJws the device-signed rcapture the packet's face BIR came from
     */
    public RecheckReport verify(String evidenceJws, String captureJws) {
        RecheckReport report = new RecheckReport();
        EvidenceRecord ev;
        try {
            ev = EvidenceSigner.verify(evidenceJws, evidenceTrust);
        } catch (EvidenceException e) {
            report.tamper("EVIDENCE_SIGNATURE", e.getMessage());
            return report;
        }
        JsonNode j = ev.json();

        // 1. The capture: device signature, nonce echo, and H_final binding it to the evidence's frame chain.
        try {
            JsonNode capture = CanonicalJson.mapper().readTree(Jws.verify(captureJws, deviceTrust).payloadText());
            if (!capture.path("transactionId").asText().equals(j.path("sessionNonce").asText())) {
                report.tamper("NONCE_ECHO", "the capture was not made for this evidence's session");
            }
        } catch (Exception e) {
            report.tamper("CAPTURE_SIGNATURE", e.getMessage());
        }
        String recorded = j.path("binding").path("finalHash").asText("");
        if (recorded.isEmpty()) {
            report.review("NO_BINDING", "the evidence records no binding of a capture");
        } else if (!recorded.equals(SbiCaptureChain.finalHash(j.path("hashChainHead").asText(), captureJws))) {
            report.tamper("CHAIN_BINDING", "H_final does not bind this frame-chain head to this capture");
        }

        // 2. Seed commitment: the revealed seed must be the one committed to before the session.
        JsonNode ch = j.path("challenges");
        if (!ch.isMissingNode()) {
            try {
                byte[] seed = unhex(ch.path("seed").asText());
                if (!hex(MessageDigest.getInstance("SHA-256").digest(seed)).equals(ch.path("seedCommitment").asText())) {
                    report.tamper("SEED_COMMITMENT", "the revealed seed is not the committed one");
                } else {
                    regenerate(ch, seed, report);
                }
            } catch (Exception e) {
                report.tamper("SEED_COMMITMENT", e.getMessage());
            }
        }

        // 3. Approved configuration.
        j.path("models").fields().forEachRemaining(m -> {
            if (!approvedModelHashes.contains(m.getValue().asText())) {
                report.review("UNAPPROVED_MODEL", m.getKey() + " sha256 " + m.getValue().asText());
            }
        });
        if (!approvedPolicyHashes.contains(j.path("policy").path("sha256").asText())) {
            report.review("UNAPPROVED_POLICY", "policy sha256 " + j.path("policy").path("sha256").asText());
        }

        // 4. Re-score the samples.
        JsonNode samples = j.path("samples");
        if (samples.isMissingNode() || serverKey == null || scorer == null) {
            report.review("NOT_RESCORED", samples.isMissingNode() ? "no sample crops" : "no server key or model");
            return report;
        }
        List<Double> scores = new ArrayList<>();
        try {
            for (byte[] jpeg : SampleCrypto.decrypt(samples, serverKey)) {
                scores.add(scorer.pLive(decode(jpeg)));
            }
        } catch (Exception e) {
            report.tamper("SAMPLES", e.getMessage());
            return report;
        }
        double median = median(scores);
        report.serverPLive(median);
        boolean clientAccepted = "ACCEPTED".equals(j.path("outcome").asText());
        if (clientAccepted && median < serverRejectBelow) {
            report.review("CLIENT_SERVER_DISAGREEMENT", String.format("client accepted; server P(live) %.3f", median));
        }
        return report;
    }

    /**
     * Draws the plan again from the revealed seed and what the evidence says it was drawn from, and compares it step
     * for step (Bible Part 10): a client that showed an easier or pre-recordable sequence than its seed draws is caught.
     */
    static void regenerate(JsonNode ch, byte[] seed, RecheckReport report) {
        if (!ch.has("pool")) {
            report.review("PLAN_NOT_REGENERABLE", "the evidence does not record what its challenge plan was drawn from");
            return;
        }
        java.util.List<pramaan.core.policy.ChallengeKind> pool = new java.util.ArrayList<>();
        ch.path("pool").forEach(n -> pool.add(pramaan.core.policy.ChallengeKind.valueOf(n.asText())));
        java.util.Set<pramaan.core.policy.ChallengeKind> unlocking = java.util.EnumSet.noneOf(pramaan.core.policy.ChallengeKind.class);
        ch.path("unlocking").forEach(n -> unlocking.add(pramaan.core.policy.ChallengeKind.valueOf(n.asText())));
        java.util.Set<pramaan.core.source.Capability> caps = java.util.EnumSet.noneOf(pramaan.core.source.Capability.class);
        ch.path("capabilities").forEach(n -> caps.add(pramaan.core.source.Capability.valueOf(n.asText())));
        java.util.Set<pramaan.core.policy.AccessibilityProfile> profiles =
                java.util.EnumSet.noneOf(pramaan.core.policy.AccessibilityProfile.class);
        ch.path("accessibility").forEach(n -> profiles.add(pramaan.core.policy.AccessibilityProfile.valueOf(n.asText())));
        pramaan.core.source.SessionCapabilities sc = pramaan.core.source.SessionCapabilities.of(ch.path("fps").asDouble(),
                new pramaan.core.source.DeviceCapabilities(new pramaan.core.source.DeviceMetadata("server", "0", null,
                        pramaan.core.source.DeviceLevel.L1, null, null, null), caps));
        pramaan.core.challenge.ChallengePlan again = pramaan.core.challenge.ChallengeGenerator.replay(seed).plan(pool,
                ch.path("minSteps").asInt(), ch.path("maxSteps").asInt(), sc,
                pramaan.core.challenge.AccessibilityAdjustments.of(profiles), unlocking);
        JsonNode recorded = ch.path("steps");
        boolean same = recorded.size() == again.steps().size() && ch.path("flash").asBoolean() == again.flash().isPresent();
        for (int i = 0; same && i < recorded.size(); i++) {
            pramaan.core.challenge.Challenge c = again.steps().get(i);
            JsonNode r = recorded.get(i);
            same = c.kind().name().equals(r.path("kind").asText())
                    && (c.direction() == null ? !r.has("direction") : c.direction().name().equals(r.path("direction").asText()))
                    && c.targetDeg() == r.path("targetDeg").asInt(0) && c.count() == r.path("count").asInt()
                    && c.holdMs() == r.path("holdMs").asInt() && c.gazeCell() == r.path("gazeCell").asInt()
                    && c.onsetDelayMs() == r.path("onsetDelayMs").asInt();
        }
        if (!same) {
            report.tamper("PLAN_MISMATCH", "the recorded challenges are not the ones the committed seed draws");
        }
    }

    private static RgbImage decode(byte[] jpeg) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        byte[] rgb = new byte[img.getWidth() * img.getHeight() * 3];
        int[] argb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        for (int i = 0; i < argb.length; i++) {
            rgb[3 * i] = (byte) (argb[i] >> 16);
            rgb[3 * i + 1] = (byte) (argb[i] >> 8);
            rgb[3 * i + 2] = (byte) argb[i];
        }
        return RgbImage.of(img.getWidth(), img.getHeight(), rgb);
    }

    private static double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.toString();
    }
}
