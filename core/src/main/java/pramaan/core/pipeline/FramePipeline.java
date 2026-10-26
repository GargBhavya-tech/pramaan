/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.function.LongSupplier;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.gate.FaceGate;
import pramaan.core.gate.FaceTracker;
import pramaan.core.image.RgbImage;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.quality.Guidance;
import pramaan.core.quality.QualityAssessor;
import pramaan.core.quality.QualityComponent;
import pramaan.core.quality.QualityReport;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameDecoder;

/**
 * Per-frame processing (Build Map #28): decode, detect every frame, gate, track, quality, and the passive
 * model on a cadence set by the measured frame rate — about ten passive scores a second at most, every
 * frame at 3 fps. Stage latencies are recorded through an injected monotonic time source, so the numbers
 * are measurements (core never reads the system clock itself).
 */
public final class FramePipeline {

    /** What one frame produced. {@code passive} is NaN when the model did not run on this frame. */
    public static final class Result {
        public final FaceGate.Status gate;
        public final long trackId;
        public final boolean trackReset;
        public final QualityReport quality;
        public final Guidance guidance;
        public final double passive;
        /** The decoded frame, for the cues that need pixels (moiré, landmarks, SFace). */
        public final RgbImage image;
        /** The one face when {@code gate} is ONE_FACE, otherwise null. */
        public final FaceBox face;

        Result(FaceGate.Status gate, long trackId, boolean trackReset, QualityReport quality, Guidance guidance, double passive,
                RgbImage image, FaceBox face) {
            this.gate = gate;
            this.trackId = trackId;
            this.trackReset = trackReset;
            this.quality = quality;
            this.guidance = guidance;
            this.passive = passive;
            this.image = image;
            this.face = face;
        }
    }

    private final FrameDecoder decoder;
    private final YuNetDetector detector;
    private final PassiveScorer passive;
    private final QualityAssessor quality = new QualityAssessor();
    private final FaceGate gate = new FaceGate();
    private final FaceTracker tracker;
    private final LongSupplier nanoTime;
    private final int detectStride;
    private final int passiveEvery;
    private final LatencyRecorder latency = new LatencyRecorder();
    private FaceBox lastTrackedFace;
    private long frames;

    public FramePipeline(FrameDecoder decoder, YuNetDetector detector, PassiveScorer passive, double measuredFps,
            LongSupplier monotonicNanos) {
        this(decoder, detector, passive, measuredFps, monotonicNanos, Integer.getInteger("pramaan.detect.stride", 1));
    }

    public FramePipeline(FrameDecoder decoder, YuNetDetector detector, PassiveScorer passive, double measuredFps,
            LongSupplier monotonicNanos, int detectStride) {
        this.decoder = decoder;
        this.detector = detector;
        this.passive = passive;
        this.nanoTime = monotonicNanos;
        this.detectStride = Math.max(1, detectStride);
        this.passiveEvery = Math.max(1, (int) Math.round(measuredFps / 10.0));
        this.tracker = new FaceTracker(FaceTracker.gapFor(measuredFps));
    }

    public Result process(Frame frame) throws IOException, ai.onnxruntime.OrtException {
        long t0 = nanoTime.getAsLong();
        RgbImage image = decoder.decode(frame);
        long t1 = nanoTime.getAsLong();
        List<FaceBox> faces;
        if (detectStride > 1 && frames % detectStride != 0 && lastTrackedFace != null && tracker.currentTrackId() != 0) {
            faces = java.util.Collections.singletonList(lastTrackedFace);
        } else {
            faces = detector.detect(image);
        }
        long t2 = nanoTime.getAsLong();
        FaceGate.Result g = gate.update(faces, frame.hostArrival());
        FaceTracker.Update track = tracker.update(g.status() == FaceGate.Status.ONE_FACE ? g.primary() : null, frame.hostArrival());
        lastTrackedFace = g.status() == FaceGate.Status.ONE_FACE ? g.primary() : null;
        latency.record("decode", t1 - t0);
        latency.record("detect", t2 - t1);

        // The gate keeps reporting ONE_FACE for a short debounce after the face is lost, with no box: that frame has no face to
        // assess, so it is a no-face frame (a live camera produces these whenever the face drops out for an instant).
        if (g.status() != FaceGate.Status.ONE_FACE || g.primary() == null) {
            frames++;
            FaceGate.Status shown = g.primary() == null && g.status() == FaceGate.Status.ONE_FACE ? FaceGate.Status.NO_FACE : g.status();
            return new Result(shown, track.trackId(), track.previousTrackLost(), null, Guidance.forGate(shown), Double.NaN,
                    image, null);
        }
        QualityReport q = quality.assess(image, g.primary(), false);
        long t3 = nanoTime.getAsLong();
        latency.record("quality", t3 - t2);

        double p = Double.NaN;
        if (q.passes() && frames % passiveEvery == 0) {
            p = passive.liveProbability(image, g.primary());
            latency.record("passive", nanoTime.getAsLong() - t3);
        }
        frames++;
        double side = q.scores().getOrDefault(QualityComponent.BRIGHTNESS, 1.0) < QualityReport.PASS ? darkOrBright(image) : 0;
        return new Result(g.status(), track.trackId(), track.previousTrackLost(), q, Guidance.forQuality(q, side), p, image, g.primary());
    }

    public LatencyRecorder latency() {
        return latency;
    }

    public int passiveEvery() {
        return passiveEvery;
    }

    private static double darkOrBright(RgbImage image) {
        float[] g = pramaan.core.image.ImageOps.luma(pramaan.core.image.ImageOps.resize(image, 64, 48));
        return pramaan.core.image.ImageOps.meanColumns(g, 64, 48, 0, 64) < 128 ? -1 : 1;
    }
}
