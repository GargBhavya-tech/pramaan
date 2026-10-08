/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import pramaan.core.pad.CueResult;

/**
 * Everything an attempt has gathered since its face was acquired: one track, one frame hash chain, the
 * cue results. Replaced wholesale — never edited — when the face is lost, so evidence cannot span a
 * discontinuity (Bible Part 12; Build Map #52).
 */
final class AttemptEvidence {

    static final long UNBOUND = 0;

    private final FrameHashChain chain;
    private final List<CueResult> cues = new ArrayList<>();
    private final List<float[]> embeddings = new ArrayList<>();
    private long trackId = UNBOUND;
    private long lastFrameMillis = Long.MIN_VALUE;

    AttemptEvidence(SessionNonce nonce) {
        this.chain = new FrameHashChain(nonce);
    }

    /** True if the frame belongs to this evidence's track (binding it on the first frame). */
    boolean accepts(long track) {
        return trackId == UNBOUND || trackId == track;
    }

    void addFrame(byte[] frame, long epochMillis, long track) {
        if (!accepts(track)) {
            throw new IllegalStateException("evidence for track " + trackId + " cannot take a frame of track " + track);
        }
        trackId = track;
        chain.append(frame, epochMillis);
        lastFrameMillis = epochMillis;
    }

    void addCue(CueResult r) {
        cues.add(r);
    }

    void addEmbedding(float[] e) {
        embeddings.add(e.clone());
    }

    List<float[]> embeddings() {
        return Collections.unmodifiableList(new ArrayList<>(embeddings));
    }

    /** Arrival time of the newest hashed frame, or Long.MIN_VALUE if none. */
    long lastFrameMillis() {
        return lastFrameMillis;
    }

    long trackId() {
        return trackId;
    }

    long frames() {
        return chain.frames();
    }

    String head() {
        return chain.head();
    }

    List<CueResult> cues() {
        return Collections.unmodifiableList(new ArrayList<>(cues));
    }
}
