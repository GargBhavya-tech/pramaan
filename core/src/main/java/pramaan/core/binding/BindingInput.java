/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import pramaan.core.source.SignedCapture;

/**
 * Everything the four locks look at (Bible Part 13). Built by the session from its own attempt evidence
 * ({@code LivenessSession.bindingInput}), so the nonce, chain head, track and embeddings are the attempt's,
 * not the caller's.
 */
public final class BindingInput {

    private final String nonce;
    private final String evidenceHead;
    private final long evidenceTrackId;
    private final long currentTrackId;
    private final Instant lastEvidenceFrameAt;
    private final Instant captureRequestedAt;
    private final List<float[]> referenceEmbeddings;
    private final float[] captureEmbedding;
    private final SignedCapture capture;
    private final List<SignedCapture> earlierCaptures;
    private final float[] operatorEmbedding;
    private SignedStreamLock streamLock;

    /**
     * @param captureEmbedding embedding of the face in the signed capture, or null if no face was found there
     * @param earlierCaptures this registration session's earlier signed captures, oldest first
     */
    public BindingInput(String nonce, String evidenceHead, long evidenceTrackId, long currentTrackId,
            Instant lastEvidenceFrameAt, Instant captureRequestedAt, List<float[]> referenceEmbeddings,
            float[] captureEmbedding, SignedCapture capture, List<SignedCapture> earlierCaptures) {
        this(nonce, evidenceHead, evidenceTrackId, currentTrackId, lastEvidenceFrameAt, captureRequestedAt,
                referenceEmbeddings, captureEmbedding, capture, earlierCaptures, null);
    }

    /**
     * @param operatorEmbedding the logged-in operator's face, for a resident capture; null when there is none to
     *     guard against (Bible Part 15.3)
     */
    public BindingInput(String nonce, String evidenceHead, long evidenceTrackId, long currentTrackId,
            Instant lastEvidenceFrameAt, Instant captureRequestedAt, List<float[]> referenceEmbeddings,
            float[] captureEmbedding, SignedCapture capture, List<SignedCapture> earlierCaptures,
            float[] operatorEmbedding) {
        this.operatorEmbedding = operatorEmbedding == null ? null : operatorEmbedding.clone();
        this.nonce = Objects.requireNonNull(nonce, "nonce");
        this.evidenceHead = Objects.requireNonNull(evidenceHead, "evidenceHead");
        this.evidenceTrackId = evidenceTrackId;
        this.currentTrackId = currentTrackId;
        this.lastEvidenceFrameAt = lastEvidenceFrameAt;
        this.captureRequestedAt = Objects.requireNonNull(captureRequestedAt, "captureRequestedAt");
        this.referenceEmbeddings = Collections.unmodifiableList(new ArrayList<>(referenceEmbeddings));
        this.captureEmbedding = captureEmbedding == null ? null : captureEmbedding.clone();
        this.capture = Objects.requireNonNull(capture, "capture");
        this.earlierCaptures = Collections.unmodifiableList(new ArrayList<>(earlierCaptures));
    }

    public String nonce() {
        return nonce;
    }

    public String evidenceHead() {
        return evidenceHead;
    }

    public long evidenceTrackId() {
        return evidenceTrackId;
    }

    public long currentTrackId() {
        return currentTrackId;
    }

    /** Null if no frame was ever analysed in this attempt. */
    public Instant lastEvidenceFrameAt() {
        return lastEvidenceFrameAt;
    }

    public Instant captureRequestedAt() {
        return captureRequestedAt;
    }

    public List<float[]> referenceEmbeddings() {
        return referenceEmbeddings;
    }

    public float[] captureEmbedding() {
        return captureEmbedding == null ? null : captureEmbedding.clone();
    }

    public SignedCapture capture() {
        return capture;
    }

    public List<SignedCapture> earlierCaptures() {
        return earlierCaptures;
    }

    /** The logged-in operator's face embedding, or null. */
    public float[] operatorEmbedding() {
        return operatorEmbedding == null ? null : operatorEmbedding.clone();
    }

    /**
     * A copy of this input that also carries the device's authenticated-stream lock (SBI Liveness Profile, Bible
     * Part 41); the binder then adds the stream lock to the four of Part 13.
     */
    public BindingInput withStreamLock(SignedStreamLock lock) {
        BindingInput copy = new BindingInput(nonce, evidenceHead, evidenceTrackId, currentTrackId, lastEvidenceFrameAt,
                captureRequestedAt, referenceEmbeddings, captureEmbedding, capture, earlierCaptures, operatorEmbedding);
        copy.streamLock = lock;
        return copy;
    }

    /** The stream lock, or null when the device does not speak the profile. */
    public SignedStreamLock streamLock() {
        return streamLock;
    }
}
