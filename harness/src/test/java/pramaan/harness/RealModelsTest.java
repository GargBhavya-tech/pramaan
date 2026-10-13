/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.image.RgbImage;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.quality.QualityAssessor;
import pramaan.core.quality.QualityReport;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;
import pramaan.desktop.ImageIoFrameDecoder;
import pramaan.harness.corpus.ClipLoader;

/**
 * The real models on real frames (Build Map #19, #21, #24): MOSIP MockMDS's stream, which shows one real
 * person. Skipped, not passed, when models/ or the (uncommitted) stream fixture is missing.
 *
 * <p>This proves the models load and the preprocessing is wired sanely. It says nothing about accuracy:
 * that needs the corpus (#65) and the golden set (#27).
 */
class RealModelsTest {

    private static final Path MODELS = Path.of("..", "models");
    private static final Path STREAM = Path.of("..", "sbi", "testdata", "stream-sample.mjpeg");

    private static RgbImage firstFrame() throws IOException {
        assumeTrue(Models.present(MODELS), "models/ missing: see docs/licences.md and tools/export_minifasnet.py");
        assumeTrue(Files.exists(STREAM), "stream fixture missing: run MockMDS and tools/capture_sbi_fixtures.py");
        byte[] jpeg = ClipLoader.load(STREAM).get(0);
        return new ImageIoFrameDecoder().decode(new Frame(jpeg, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                java.time.Instant.EPOCH, 0, null));
    }

    @Test
    void yunetFindsExactlyTheOnePersonAndTheirFeaturesAreWhereAFaceShouldBe() throws Exception {
        RgbImage frame = firstFrame();
        try (YuNetDetector detector = Models.detector(MODELS)) {
            List<FaceBox> faces = detector.detect(frame);
            assertEquals(1, faces.size(), "one person in the MockMDS image");
            FaceBox f = faces.get(0);
            assertTrue(f.interOcularDistance() > 25, "IOD " + f.interOcularDistance());
            assertTrue(f.landmark(0, 0) < f.landmark(1, 0), "subject's right eye is on the image's left");
            assertTrue(f.landmark(2, 1) > f.landmark(0, 1) && f.landmark(3, 1) > f.landmark(2, 1),
                    "eyes above nose above mouth");
            System.out.printf("YuNet: box %.0f,%.0f %.0fx%.0f score %.3f IOD %.1f px%n", f.x(), f.y(), f.width(), f.height(),
                    f.score(), f.interOcularDistance());

            QualityReport q = new QualityAssessor().assess(frame, f, false);
            System.out.println("Quality: Q=" + String.format("%.2f", q.q()) + " worst=" + q.worst() + " " + q.scores());
        }
    }

    @Test
    void passiveScoresAreProbabilitiesAndStableOnARepeatedImage() throws Exception {
        RgbImage frame = firstFrame();
        try (YuNetDetector detector = Models.detector(MODELS); PassiveScorer passive = Models.passive(MODELS)) {
            FaceBox f = detector.detect(frame).get(0);
            double p1 = passive.liveProbability(frame, f);
            double p2 = passive.liveProbability(frame, f);
            assertTrue(p1 > 0 && p1 < 1, "p=" + p1);
            assertEquals(p1, p2, 1e-9, "deterministic on identical input");
            System.out.printf("MiniFASNet raw p(live) on the MockMDS image: %.4f (uncalibrated, not an accuracy claim)%n", p1);
        }
    }

    @Test
    void aCorruptFrameFailsToDecodeInsteadOfBecomingAFace() {
        Frame garbage = new Frame(new byte[64], PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, java.time.Instant.EPOCH, 0, null);
        assertThrows(IOException.class, () -> new ImageIoFrameDecoder().decode(garbage));
    }
}
