/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.FaceDetector;
import pramaan.core.gate.FaceGate;
import pramaan.core.image.RgbImage;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameDecoder;
import pramaan.core.source.PixelFormat;

class FramePipelineStrideTest {

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    private static FaceBox sampleFace(float x, float y, float iod) {
        return new FaceBox(x, y, iod * 2.5f, iod * 3.0f, 0.98f,
                new float[] {x + iod * 0.75f, y + iod, x + iod * 1.75f, y + iod, x + iod * 1.25f, y + iod * 1.5f,
                        x + iod * 0.8f, y + iod * 2.0f, x + iod * 1.7f, y + iod * 2.0f});
    }

    private static Frame frameAt(int frameIndex, double fps) {
        long ms = Math.round(frameIndex * 1000.0 / fps);
        return new Frame(new byte[16], PixelFormat.JPEG, 640, 480, Frame.NO_SENSOR_TIMESTAMP,
                T0.plusMillis(ms), frameIndex, null);
    }

    private static final FrameDecoder DUMMY_DECODER = frame -> {
        // Return 640x480 black image with a neutral face area
        byte[] rgb = new byte[640 * 480 * 3];
        return RgbImage.of(640, 480, rgb);
    };

    private static final class CountingDetector implements FaceDetector {
        private final AtomicInteger calls = new AtomicInteger();
        private List<FaceBox> nextDetections = new ArrayList<>();

        void setNextDetections(List<FaceBox> faces) {
            this.nextDetections = faces;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public List<FaceBox> detect(RgbImage image) {
            calls.incrementAndGet();
            return nextDetections;
        }
    }

    @Test
    void strideOneRunsDetectorOnEveryFrame() throws Exception {
        CountingDetector detector = new CountingDetector();
        detector.setNextDetections(List.of(sampleFace(200, 150, 70)));
        FramePipeline pipeline = new FramePipeline(DUMMY_DECODER, detector, null, 3.0, System::nanoTime, 1);

        for (int i = 0; i < 4; i++) {
            FramePipeline.Result r = pipeline.process(frameAt(i, 3.0));
            assertEquals(FaceGate.Status.ONE_FACE, r.gate);
            assertNotNull(r.face);
        }
        assertEquals(4, detector.callCount(), "Stride 1 must run detector on all 4 frames");
    }

    @Test
    void strideTwoSkipsDetectorOnAlternateFramesWhenFaceIsTracked() throws Exception {
        CountingDetector detector = new CountingDetector();
        FaceBox face = sampleFace(200, 150, 70);
        detector.setNextDetections(List.of(face));
        FramePipeline pipeline = new FramePipeline(DUMMY_DECODER, detector, null, 3.0, System::nanoTime, 2);

        // Frame 0: Initial detection, detector runs
        FramePipeline.Result r0 = pipeline.process(frameAt(0, 3.0));
        assertEquals(1, detector.callCount(), "Frame 0 must invoke detector");
        assertEquals(FaceGate.Status.ONE_FACE, r0.gate);
        assertEquals(face, r0.face);
        long trackId = r0.trackId;
        assertTrue(trackId > 0, "Track ID must be established");

        // Frame 1: Stride 2 frame-skip, detector is NOT invoked, box is reused
        FramePipeline.Result r1 = pipeline.process(frameAt(1, 3.0));
        assertEquals(1, detector.callCount(), "Frame 1 must SKIP detector invocation (box reuse)");
        assertEquals(FaceGate.Status.ONE_FACE, r1.gate, "Gate must still report ONE_FACE on skipped frame");
        assertEquals(face, r1.face, "Face box must be preserved on skipped frame");
        assertEquals(trackId, r1.trackId, "Track ID must remain continuous across skipped frame");
        assertFalse(r1.trackReset, "Track must not reset on skipped frame");

        // Frame 2: Evaluated frame, detector runs again
        FramePipeline.Result r2 = pipeline.process(frameAt(2, 3.0));
        assertEquals(2, detector.callCount(), "Frame 2 must invoke detector");
        assertEquals(FaceGate.Status.ONE_FACE, r2.gate);
        assertEquals(trackId, r2.trackId);

        // Frame 3: Stride 2 frame-skip, detector is NOT invoked
        FramePipeline.Result r3 = pipeline.process(frameAt(3, 3.0));
        assertEquals(2, detector.callCount(), "Frame 3 must SKIP detector invocation");
        assertEquals(FaceGate.Status.ONE_FACE, r3.gate);
        assertEquals(trackId, r3.trackId);
    }

    @Test
    void strideTwoDoesNotSkipWhenNoFaceIsCurrentlyTracked() throws Exception {
        CountingDetector detector = new CountingDetector();
        // Return no face
        detector.setNextDetections(List.of());
        FramePipeline pipeline = new FramePipeline(DUMMY_DECODER, detector, null, 3.0, System::nanoTime, 2);

        // Frame 0: Detector runs, no face found
        FramePipeline.Result r0 = pipeline.process(frameAt(0, 3.0));
        assertEquals(1, detector.callCount());
        assertEquals(FaceGate.Status.NO_FACE, r0.gate);

        // Frame 1: Even though frameIndex % 2 != 0, detector must STILL run because no face is tracked
        FramePipeline.Result r1 = pipeline.process(frameAt(1, 3.0));
        assertEquals(2, detector.callCount(), "Detector must run on frame 1 because no face is tracked");
        assertEquals(FaceGate.Status.NO_FACE, r1.gate);
    }

    @Test
    void strideTwoRecalibratesWhenFaceMovesOnSampledFrames() throws Exception {
        CountingDetector detector = new CountingDetector();
        FaceBox pos0 = sampleFace(200, 150, 70);
        FaceBox pos1 = sampleFace(210, 152, 70); // Slight movement within IOU overlap
        detector.setNextDetections(List.of(pos0));
        FramePipeline pipeline = new FramePipeline(DUMMY_DECODER, detector, null, 3.0, System::nanoTime, 2);

        // Frame 0: at pos0
        FramePipeline.Result r0 = pipeline.process(frameAt(0, 3.0));
        assertEquals(pos0, r0.face);

        // Frame 1: skipped, reuses pos0
        FramePipeline.Result r1 = pipeline.process(frameAt(1, 3.0));
        assertEquals(pos0, r1.face);

        // Frame 2: detector runs and finds moved face at pos1
        detector.setNextDetections(List.of(pos1));
        FramePipeline.Result r2 = pipeline.process(frameAt(2, 3.0));
        assertEquals(pos1, r2.face, "Sampled frame must update to the new position");
        assertEquals(r0.trackId, r2.trackId, "Small displacement must maintain same track ID");

        // Frame 3: skipped, reuses updated pos1
        FramePipeline.Result r3 = pipeline.process(frameAt(3, 3.0));
        assertEquals(pos1, r3.face, "Skipped frame must reuse the updated position");
    }
}
