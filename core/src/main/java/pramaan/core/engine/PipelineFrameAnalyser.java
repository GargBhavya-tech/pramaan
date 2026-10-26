/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.io.IOException;
import java.util.Objects;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.detect.FaceBox;
import pramaan.core.gate.FaceGate;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.landmarks.LandmarkProvider;
import pramaan.core.evidence.SampleSelector;
import pramaan.core.pad.ColorTextureCue;
import pramaan.core.pad.MoireCue;
import pramaan.core.pipeline.FramePipeline;
import pramaan.core.source.Frame;

/**
 * The desktop perception stack behind {@link FrameAnalyser}: {@link FramePipeline} (decode, YuNet, gate,
 * tracker, quality, passive on its cadence), then dense landmarks on every single-face frame — a turned head
 * fails the pose check, and the turn is exactly what a challenge needs to see — and, on the frames the passive
 * model scored, moiré on the native-resolution face and an SFace embedding for the identity lock.
 */
public final class PipelineFrameAnalyser implements FrameAnalyser {

    private final FramePipeline pipeline;
    private final LandmarkProvider landmarks;
    private final SFaceEmbedder sface;
    private final MoireCue moire;
    private final ColorTextureCue colorTexture;
    private final SceneContext scene;

    public PipelineFrameAnalyser(FramePipeline pipeline, LandmarkProvider landmarks, SFaceEmbedder sface) {
        this(pipeline, landmarks, sface, new ColorTextureCue(null, 0));
    }

    /** @param colorTexture the colour-texture cue (#30); untrained, it abstains on every frame */
    public PipelineFrameAnalyser(FramePipeline pipeline, LandmarkProvider landmarks, SFaceEmbedder sface,
            ColorTextureCue colorTexture) {
        this(pipeline, landmarks, sface, colorTexture, null);
    }

    /**
     * @param scene this station's stored backdrop (Bible Part 43), or null when none was ever captured; with it, the
     *     scene, white-point and light-direction cues are evaluated on the frames it picks
     */
    public PipelineFrameAnalyser(FramePipeline pipeline, LandmarkProvider landmarks, SFaceEmbedder sface,
            ColorTextureCue colorTexture, SceneContext scene) {
        this(pipeline, landmarks, sface, colorTexture, scene, new MoireCue());
    }

    /** @param moire the moire cue calibrated for the connected camera (from the operating-point table) */
    public PipelineFrameAnalyser(FramePipeline pipeline, LandmarkProvider landmarks, SFaceEmbedder sface,
            ColorTextureCue colorTexture, SceneContext scene, MoireCue moire) {
        this.moire = Objects.requireNonNull(moire, "moire");
        this.scene = scene;
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.landmarks = Objects.requireNonNull(landmarks, "landmarks");
        this.sface = Objects.requireNonNull(sface, "sface");
        this.colorTexture = Objects.requireNonNull(colorTexture, "colorTexture");
    }

    /** Run the embedding model on one scored frame in this many. */
    static final int EMBED_EVERY = 3;

    private long scored;

    @Override
    public FrameObservation analyse(Frame frame) throws Exception {
        FramePipeline.Result r;
        try {
            r = pipeline.process(frame);
        } catch (IOException undecodable) {
            return FrameObservation.undecodable();
        }
        FrameObservation.Builder b = FrameObservation.builder(r.gate, r.trackId).trackReset(r.trackReset);
        if (r.gate != FaceGate.Status.ONE_FACE) {
            return b.build();
        }
        b.quality(r.quality.passes(), r.guidance).landmarks(landmarks.locate(r.image, r.face));
        // The flash cue reads the face's colour on every single-face frame, even one the quality gate would not
        // score: a coloured light changes the very lighting the gate judges.
        double[][] regions = FaceRegions.sample(r.image, r.face);
        b.regions(regions[0], regions[1], regions[2]);
        if (r.quality.passes() && !Double.isNaN(r.passive)) {
            RgbImage face = nativeCrop(r.image, r.face);
            b.passive(r.passive).moire(moire.evaluate(face));
            b.cue(colorTexture.evaluate(face));
            // SFace is the costliest model (about a third of a frame on a laptop CPU) and the engine only collects embeddings
            // for the same-face lock, so it runs on the first scored frame and then on every EMBED_EVERY-th one.
            if (scored++ % EMBED_EVERY == 0) {
                b.embedding(sface.embed(r.image, r.face.fivePoints()));
            }
            b.sample(SampleSelector.contextCrop(r.image, r.face), r.quality.q());
            if (scene != null) {
                scene.evaluate(r.image, r.face, face).forEach(b::cue);
            }
        }
        return b.build();
    }

    /** The face box cut from the decoded frame with no rescaling: resampling would blur the moiré it looks for. */
    static RgbImage nativeCrop(RgbImage image, FaceBox face) {
        int x0 = Math.round(face.x());
        int y0 = Math.round(face.y());
        return ImageOps.crop(image, x0, y0, x0 + Math.round(face.width()) - 1, y0 + Math.round(face.height()) - 1);
    }
}
