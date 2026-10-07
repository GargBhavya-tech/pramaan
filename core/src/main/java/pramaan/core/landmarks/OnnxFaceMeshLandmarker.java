/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.landmarks;

import ai.onnxruntime.OrtException;
import java.util.Map;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * MediaPipe FaceMesh V2 (Apache-2.0) converted to ONNX with tf2onnx; parity with the original TFLite model
 * was 1.5e-4 px on a 256 px crop (docs/adr/0002-landmarks.md).
 *
 * <p>Input: a square crop of 1.5x the detector box around its centre, resized to 256x256, RGB, NHWC,
 * values in [0, 1]. Outputs: 478x3 landmarks in crop pixels (mapped back to frame pixels here) and a
 * face-presence logit.
 */
public final class OnnxFaceMeshLandmarker implements LandmarkProvider {

    public static final int INPUT = 256;
    private static final double CROP_SCALE = 1.5;

    private final OnnxModel model;

    public OnnxFaceMeshLandmarker(OnnxModel model) {
        this.model = model;
    }

    @Override
    public FaceLandmarks locate(RgbImage frame, FaceBox face) throws OrtException {
        double cx = face.x() + face.width() / 2.0;
        double cy = face.y() + face.height() / 2.0;
        int side = (int) Math.round(CROP_SCALE * Math.max(face.width(), face.height()));
        int x0 = (int) Math.round(cx - side / 2.0);
        int y0 = (int) Math.round(cy - side / 2.0);
        RgbImage square = paddedCrop(frame, x0, y0, side);
        RgbImage input = ImageOps.resize(square, INPUT, INPUT);

        Map<String, OnnxModel.Output> out = model.runAll(toNhwcRgb01(input), new long[] {1, INPUT, INPUT, 3});
        float[] raw = null;
        double presenceLogit = Double.NaN;
        for (OnnxModel.Output o : out.values()) {
            float[] v = o.values();
            if (v.length == FaceLandmarks.COUNT * 3) {
                raw = v;
            } else if (v.length == 1 && o.shape().length == 4) {
                presenceLogit = v[0];
            }
        }
        if (raw == null) {
            throw new IllegalStateException("landmark output not found");
        }
        double scale = side / (double) INPUT;
        float[] xyz = new float[raw.length];
        for (int i = 0; i < FaceLandmarks.COUNT; i++) {
            xyz[3 * i] = (float) (x0 + raw[3 * i] * scale);
            xyz[3 * i + 1] = (float) (y0 + raw[3 * i + 1] * scale);
            xyz[3 * i + 2] = (float) (raw[3 * i + 2] * scale);
        }
        return new FaceLandmarks(xyz, 1 / (1 + Math.exp(-presenceLogit)));
    }

    /** A side x side crop at (x0, y0); parts outside the frame are black, so the crop stays square. */
    static RgbImage paddedCrop(RgbImage frame, int x0, int y0, int side) {
        byte[] out = new byte[side * side * 3];
        for (int y = 0; y < side; y++) {
            int fy = y0 + y;
            if (fy < 0 || fy >= frame.height()) {
                continue;
            }
            for (int x = 0; x < side; x++) {
                int fx = x0 + x;
                if (fx < 0 || fx >= frame.width()) {
                    continue;
                }
                int i = (y * side + x) * 3;
                out[i] = (byte) frame.get(fx, fy, 0);
                out[i + 1] = (byte) frame.get(fx, fy, 1);
                out[i + 2] = (byte) frame.get(fx, fy, 2);
            }
        }
        return RgbImage.of(side, side, out);
    }

    static float[] toNhwcRgb01(RgbImage img) {
        float[] out = new float[img.width() * img.height() * 3];
        int i = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                for (int c = 0; c < 3; c++) {
                    out[i++] = img.get(x, y, c) / 255f;
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            model.close();
        } catch (OrtException e) {
            throw new IllegalStateException("failed to release the landmark model", e);
        }
    }
}
