/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionState;
import pramaan.core.source.SignedCapture;

/**
 * The evidence for an accepted attempt, ready for the packet (Bible Part 14; Build Map #59-#61, ADR 0003 patch P4):
 * the record assembled from what the engine actually used — its decision, binding verdict, challenge plan and
 * revealed seed, accessibility profiles — plus the best sample crops, encrypted to the server, then signed.
 *
 * <p>How many crops, and at what size, come from the policy ({@code evidence.sampleFrames}, {@code cropSize}).
 */
public final class EvidencePackager {

    /** Signed evidence and the capture it is bound to: what travels in the packet. */
    public static final class Packet {
        private final EvidenceRecord record;
        private final String evidenceJws;
        private final SignedCapture capture;

        Packet(EvidenceRecord record, String evidenceJws, SignedCapture capture) {
            this.record = record;
            this.evidenceJws = evidenceJws;
            this.capture = capture;
        }

        public EvidenceRecord record() {
            return record;
        }

        /** The signed evidence: the value stored under {@code PRAMAAN_LIVENESS} in the face BIR's Others. */
        public String evidenceJws() {
            return evidenceJws;
        }

        public SignedCapture capture() {
            return capture;
        }
    }

    private final EvidenceSigner signer;
    private final PublicKey serverKey;
    private final JpegEncoder jpeg;
    private final SecureRandom random;

    /**
     * @param signer the Registration Client machine key in production; a development key in the scaffold
     * @param serverKey the key the sample crops are encrypted to; null leaves the crops out (the server then reports
     *     the evidence as not re-scored)
     */
    public EvidencePackager(EvidenceSigner signer, PublicKey serverKey, JpegEncoder jpeg, SecureRandom random) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.serverKey = serverKey;
        this.jpeg = Objects.requireNonNull(jpeg, "jpeg");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Only for an engine whose session reached ACCEPTED: there is no evidence of a liveness that did not pass. */
    public Packet pack(LivenessEngine engine, String operatorId, String centreId, Instant endedAt)
            throws GeneralSecurityException, IOException {
        LivenessSession s = engine.session();
        SignedCapture capture = engine.acceptedCapture().orElseThrow(
                () -> new IllegalStateException("evidence exists only for an accepted, bound capture"));
        if (s.state() != SessionState.ACCEPTED) {
            throw new IllegalStateException("evidence exists only for an accepted session, not " + s.state());
        }
        EvidenceBuilder b = EvidenceBuilder.forSession(s, engine.bundle()).device(capture.device())
                .operator(operatorId, centreId).accessibility(engine.accessibility().profiles())
                .injectionGuard(engine.boundVirtualCameraDetected())
                .injectionFindings(engine.injectionFindings(), engine.timing())
                .streamSignature(engine.streamSignature());
        engine.decision().ifPresent(b::fusion);
        engine.bindingResult().ifPresent(b::binding);
        if (engine.challengePlan().isPresent()) {
            b.challenges(engine.challengePlan().get(), engine.revealChallengeSeed());
        }
        List<byte[]> crops = crops(engine);
        if (serverKey != null && !crops.isEmpty()) {
            b.samples(SampleCrypto.encrypt(crops, serverKey, random));
        }
        EvidenceRecord record = b.build(endedAt);
        return new Packet(record, signer.sign(record), capture);
    }

    private List<byte[]> crops(LivenessEngine engine) throws IOException {
        int max = engine.bundle().policy().evidenceSampleFrames();
        int size = engine.bundle().policy().evidenceCropSize();
        List<RgbImage> picked = SampleSelector.select(engine.samples());
        List<byte[]> out = new ArrayList<>();
        for (RgbImage crop : picked.subList(0, Math.min(max, picked.size()))) {
            RgbImage sized = crop.width() == size && crop.height() == size ? crop : ImageOps.resize(crop, size, size);
            out.add(jpeg.encode(sized));
        }
        return out;
    }
}
