/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import pramaan.core.gate.FaceGate;
import pramaan.core.image.RgbImage;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.quality.Guidance;

/**
 * What perception found in one frame: everything {@link LivenessEngine} needs, and nothing it would have to
 * compute from pixels itself. Keeping perception behind {@link FrameAnalyser} is what lets the engine's
 * decisions be tested on scripted observations, and the same engine run on desktop models or on Android's.
 *
 * <p>Optional parts are null (or NaN for {@code passive}) when they were not computed on this frame: the
 * passive model runs on a cadence, landmarks can fail, and nothing but the gate exists without a face.
 */
public final class FrameObservation {

    private final boolean decoded;
    private final FaceGate.Status gate;
    private final long trackId;
    private final boolean trackReset;
    private final boolean qualityPasses;
    private final Guidance guidance;
    private final double passive;
    private final CueResult moire;
    private final List<CueResult> cues;
    private final FaceLandmarks landmarks;
    private final float[] embedding;
    private final RgbImage sampleCrop;
    private final double sampleQuality;
    private final double[][] regions;

    private FrameObservation(Builder b) {
        this.regions = b.regions;
        this.decoded = b.decoded;
        this.gate = b.gate;
        this.trackId = b.trackId;
        this.trackReset = b.trackReset;
        this.qualityPasses = b.qualityPasses;
        this.guidance = b.guidance;
        this.passive = b.passive;
        this.moire = b.moire;
        this.cues = Collections.unmodifiableList(new ArrayList<>(b.cues));
        this.landmarks = b.landmarks;
        this.embedding = b.embedding == null ? null : b.embedding.clone();
        this.sampleCrop = b.sampleCrop;
        this.sampleQuality = b.sampleQuality;
    }

    /** A frame that could not be decoded: it is counted as bad, never hashed or scored. */
    public static FrameObservation undecodable() {
        Builder b = new Builder(FaceGate.Status.NO_FACE, 0);
        b.decoded = false;
        return b.build();
    }

    /** @param trackId the face tracker's current track, 0 when no face is tracked */
    public static Builder builder(FaceGate.Status gate, long trackId) {
        return new Builder(gate, trackId);
    }

    public boolean decoded() {
        return decoded;
    }

    public FaceGate.Status gate() {
        return gate;
    }

    public long trackId() {
        return trackId;
    }

    /** True when this frame ended an earlier track: the attempt's evidence must restart. */
    public boolean trackReset() {
        return trackReset;
    }

    public boolean qualityPasses() {
        return qualityPasses;
    }

    public Guidance guidance() {
        return guidance;
    }

    /**
     * Passive live probability as the model outputs it, NaN when the model did not run on this frame. Not yet
     * calibrated: {@code PlattCalibration} (#26) is not wired into the pipeline until it is fitted on the corpus.
     */
    public double passive() {
        return passive;
    }

    public CueResult moire() {
        return moire;
    }

    /** Other per-frame cue results (colour-texture, ...), votes or abstentions; empty when none ran. */
    public List<CueResult> cues() {
        return cues;
    }

    public FaceLandmarks landmarks() {
        return landmarks;
    }

    /** SFace embedding of this frame's face, or null. */
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    /**
     * The passive model's own context around the face (Build Map #61), a candidate evidence sample crop; null when
     * none was cut on this frame. It never leaves the engine except, selected and encrypted, inside evidence.
     */
    public RgbImage sampleCrop() {
        return sampleCrop;
    }

    /** Face-image quality Q of the frame the sample crop came from; NaN without a crop. */
    public double sampleQuality() {
        return sampleQuality;
    }

    /**
     * Mean RGB (0-255) of three regions of the face in this frame, for the flash cue (Bible Part 8.6): row 0 the skin
     * area, row 1 the nose bridge, row 2 the cheeks; null when the frame did not carry them.
     */
    public double[][] regions() {
        if (regions == null) {
            return null;
        }
        double[][] copy = new double[regions.length][];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = regions[i].clone();
        }
        return copy;
    }

    /** A copy of this observation that also carries face-region colours; for analysers that add them after the fact. */
    public FrameObservation withRegions(double[] face, double[] nose, double[] cheeks) {
        Builder b = builder(gate, trackId).trackReset(trackReset);
        if (qualityPasses || guidance != null) {
            b.quality(qualityPasses, guidance);
        }
        if (!Double.isNaN(passive)) {
            b.passive(passive);
        }
        if (moire != null) {
            b.moire(moire);
        }
        cues.forEach(b::cue);
        b.landmarks(landmarks).embedding(embedding);
        if (sampleCrop != null) {
            b.sample(sampleCrop, sampleQuality);
        }
        return b.regions(face, nose, cheeks).build();
    }

    public static final class Builder {
        private double[][] regions;
        private boolean decoded = true;
        private final FaceGate.Status gate;
        private final long trackId;
        private boolean trackReset;
        private boolean qualityPasses;
        private Guidance guidance;
        private double passive = Double.NaN;
        private CueResult moire;
        private final List<CueResult> cues = new ArrayList<>();
        private FaceLandmarks landmarks;
        private float[] embedding;
        private RgbImage sampleCrop;
        private double sampleQuality = Double.NaN;

        private Builder(FaceGate.Status gate, long trackId) {
            this.gate = Objects.requireNonNull(gate, "gate");
            this.trackId = trackId;
            this.guidance = Guidance.forGate(gate);
        }

        public Builder trackReset(boolean reset) {
            this.trackReset = reset;
            return this;
        }

        public Builder quality(boolean passes, Guidance advice) {
            this.qualityPasses = passes;
            this.guidance = Objects.requireNonNull(advice, "advice");
            return this;
        }

        public Builder passive(double pLive) {
            this.passive = pLive;
            return this;
        }

        public Builder moire(CueResult result) {
            this.moire = result;
            return this;
        }

        public Builder cue(CueResult result) {
            this.cues.add(Objects.requireNonNull(result, "result"));
            return this;
        }

        public Builder landmarks(FaceLandmarks lm) {
            this.landmarks = lm;
            return this;
        }

        public Builder embedding(float[] e) {
            this.embedding = e;
            return this;
        }

        /** The face, nose-bridge and cheek mean colours of this frame (each a length-3 RGB row). */
        public Builder regions(double[] face, double[] nose, double[] cheeks) {
            this.regions = new double[][] {face.clone(), nose.clone(), cheeks.clone()};
            return this;
        }

        public Builder sample(RgbImage crop, double quality) {
            this.sampleCrop = Objects.requireNonNull(crop, "crop");
            this.sampleQuality = quality;
            return this;
        }

        public FrameObservation build() {
            if (gate != FaceGate.Status.ONE_FACE && (qualityPasses || !Double.isNaN(passive) || landmarks != null
                    || moire != null || !cues.isEmpty() || embedding != null || sampleCrop != null || regions != null)) {
                throw new IllegalStateException("only a single-face frame can carry quality, scores or landmarks");
            }
            return new FrameObservation(this);
        }
    }
}
