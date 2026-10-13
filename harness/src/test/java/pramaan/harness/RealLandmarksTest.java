/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.landmarks.OnnxFaceMeshLandmarker;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;
import pramaan.desktop.ImageIoFrameDecoder;
import pramaan.harness.corpus.ClipLoader;

/**
 * Build Map #23: desktop Java runs MediaPipe FaceMesh V2 through ONNX, and its landmarks agree with an
 * independent model (YuNet) on the same real face.
 */
class RealLandmarksTest {

    private static final Path MODELS = Path.of("..", "models");
    private static final Path MESH = MODELS.resolve("mediapipe_face_landmarks_detector.onnx");
    private static final Path STREAM = Path.of("..", "sbi", "testdata", "stream-sample.mjpeg");

    @Test
    void landmarksAgreeWithYunetAndDescribeAnOpenEyedFace() throws Exception {
        assumeTrue(Models.present(MODELS) && Files.exists(MESH), "models missing");
        assumeTrue(Files.exists(STREAM), "stream fixture missing");
        RgbImage frame = new ImageIoFrameDecoder().decode(new Frame(ClipLoader.load(STREAM).get(0), PixelFormat.JPEG, 0, 0,
                Frame.NO_SENSOR_TIMESTAMP, java.time.Instant.EPOCH, 0, null));
        try (YuNetDetector detector = Models.detector(MODELS);
                OnnxFaceMeshLandmarker mesh = new OnnxFaceMeshLandmarker(OnnxModel.load(Files.readAllBytes(MESH)))) {
            FaceBox face = detector.detect(frame).get(0);
            FaceLandmarks lm = mesh.locate(frame, face);

            double noseGap = Math.hypot(lm.x(FaceLandmarks.NOSE_TIP) - face.landmark(2, 0), lm.y(FaceLandmarks.NOSE_TIP) - face.landmark(2, 1));
            double rightEyeGap = Math.hypot(lm.x(FaceLandmarks.RIGHT_IRIS) - face.landmark(0, 0), lm.y(FaceLandmarks.RIGHT_IRIS) - face.landmark(0, 1));
            double iod = face.interOcularDistance();
            System.out.printf("FaceMesh: presence %.3f, nose gap %.1f px, right-iris gap %.1f px (IOD %.1f), EAR R %.3f L %.3f%n",
                    lm.presence(), noseGap, rightEyeGap, iod, lm.eyeAspectRatio(true), lm.eyeAspectRatio(false));

            assertTrue(lm.presence() > 0.9, "presence " + lm.presence());
            assertTrue(noseGap < 0.25 * iod, "nose tips of two independent models disagree by " + noseGap + " px");
            assertTrue(rightEyeGap < 0.25 * iod, "right eye disagrees by " + rightEyeGap + " px");
            assertTrue(lm.y(159) < lm.y(FaceLandmarks.NOSE_TIP) && lm.y(FaceLandmarks.NOSE_TIP) < lm.y(FaceLandmarks.UPPER_LIP),
                    "eyes above nose above mouth");
            assertTrue(lm.eyeAspectRatio(true) > 0.15 && lm.eyeAspectRatio(false) > 0.15, "the person's eyes are open");
        }
    }

    /** ADR 0002: the blendshape model on MediaPipe's 146-point subset of real landmarks describes the same open-eyed face. */
    @Test
    void blendshapesOnRealLandmarksDescribeAnOpenEyedFace() throws Exception {
        Path blend = MODELS.resolve("mediapipe_face_blendshapes.onnx");
        assumeTrue(Models.present(MODELS) && Files.exists(MESH) && Files.exists(blend), "models missing");
        assumeTrue(Files.exists(STREAM), "stream fixture missing");
        RgbImage frame = new ImageIoFrameDecoder().decode(new Frame(ClipLoader.load(STREAM).get(0), PixelFormat.JPEG, 0, 0,
                Frame.NO_SENSOR_TIMESTAMP, java.time.Instant.EPOCH, 0, null));
        try (YuNetDetector detector = Models.detector(MODELS);
                pramaan.core.landmarks.OnnxBlendshapeLandmarker mesh = new pramaan.core.landmarks.OnnxBlendshapeLandmarker(
                        new OnnxFaceMeshLandmarker(OnnxModel.load(Files.readAllBytes(MESH))),
                        OnnxModel.load(Files.readAllBytes(blend)))) {
            FaceLandmarks lm = mesh.locate(frame, detector.detect(frame).get(0));
            assertTrue(lm.hasBlendshapes());
            StringBuilder top = new StringBuilder();
            for (int i = 0; i < pramaan.core.landmarks.Blendshapes.COUNT; i++) {
                double v = lm.blendshape(i);
                assertTrue(v >= -1e-3 && v <= 1 + 1e-3, pramaan.core.landmarks.Blendshapes.NAMES.get(i) + " = " + v);
                if (v > 0.2) {
                    top.append(String.format(" %s=%.2f", pramaan.core.landmarks.Blendshapes.NAMES.get(i), v));
                }
            }
            double blinkL = lm.blendshape(pramaan.core.landmarks.Blendshapes.EYE_BLINK_LEFT);
            double blinkR = lm.blendshape(pramaan.core.landmarks.Blendshapes.EYE_BLINK_RIGHT);
            System.out.printf("Blendshapes: eyeBlink L %.3f R %.3f; above 0.2:%s%n", blinkL, blinkR, top);
            assertTrue(blinkL < 0.5 && blinkR < 0.5, "open eyes must not vote closed");
        }
    }
}
