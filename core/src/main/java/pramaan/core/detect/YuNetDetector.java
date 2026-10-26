/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.detect;

import ai.onnxruntime.OrtException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * YuNet face detection (OpenCV Zoo, MIT; models/face_detection_yunet_2023mar.onnx), with the prior
 * decoding and non-maximum suppression that OpenCV's FaceDetectorYN normally does, reimplemented here
 * because core cannot use OpenCV (Build Map #19).
 *
 * <p>Input: the frame resized to 640x640, BGR, CHW, raw 0-255. Outputs per stride s in {8, 16, 32}:
 * cls, obj [N,1], bbox [N,4], kps [N,10] over an (640/s)^2 grid.
 */
public final class YuNetDetector implements FaceDetector, AutoCloseable {

    public static final int INPUT = 640;
    private static final int[] STRIDES = {8, 16, 32};

    private final OnnxModel model;
    private final float scoreThreshold;
    private final float nmsIou;

    public YuNetDetector(OnnxModel model, float scoreThreshold, float nmsIou) {
        this.model = model;
        this.scoreThreshold = scoreThreshold;
        this.nmsIou = nmsIou;
    }

    /** Faces in the image's own pixel coordinates, best first. */
    public List<FaceBox> detect(RgbImage image) throws OrtException {
        RgbImage resized = ImageOps.resize(image, INPUT, INPUT);
        Map<String, OnnxModel.Output> out = model.runAll(ImageOps.toChwBgr(resized), new long[] {1, 3, INPUT, INPUT});
        List<FaceBox> raw = new ArrayList<>();
        for (int s : STRIDES) {
            raw.addAll(decode(s, INPUT / s, out.get("cls_" + s).values(), out.get("obj_" + s).values(),
                    out.get("bbox_" + s).values(), out.get("kps_" + s).values(), scoreThreshold));
        }
        List<FaceBox> kept = nms(raw, nmsIou);
        float sx = (float) image.width() / INPUT;
        float sy = (float) image.height() / INPUT;
        List<FaceBox> result = new ArrayList<>(kept.size());
        for (FaceBox b : kept) {
            result.add(b.scaled(sx, sy));
        }
        return result;
    }

    /** Decodes one stride's grid of priors (OpenCV face_detect.cpp semantics). */
    static List<FaceBox> decode(int stride, int cols, float[] cls, float[] obj, float[] bbox, float[] kps, float threshold) {
        List<FaceBox> out = new ArrayList<>();
        for (int idx = 0; idx < cls.length; idx++) {
            float c = Math.max(0f, Math.min(1f, cls[idx]));
            float o = Math.max(0f, Math.min(1f, obj[idx]));
            float score = (float) Math.sqrt(c * o);
            if (score < threshold) {
                continue;
            }
            int row = idx / cols;
            int col = idx % cols;
            float cx = (col + bbox[4 * idx]) * stride;
            float cy = (row + bbox[4 * idx + 1]) * stride;
            float w = (float) Math.exp(bbox[4 * idx + 2]) * stride;
            float h = (float) Math.exp(bbox[4 * idx + 3]) * stride;
            float[] l = new float[10];
            for (int k = 0; k < 5; k++) {
                l[2 * k] = (kps[10 * idx + 2 * k] + col) * stride;
                l[2 * k + 1] = (kps[10 * idx + 2 * k + 1] + row) * stride;
            }
            out.add(new FaceBox(cx - w / 2, cy - h / 2, w, h, score, l));
        }
        return out;
    }

    static List<FaceBox> nms(List<FaceBox> boxes, float iouThreshold) {
        List<FaceBox> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(FaceBox::score).reversed());
        List<FaceBox> kept = new ArrayList<>();
        for (FaceBox b : sorted) {
            boolean overlaps = false;
            for (FaceBox k : kept) {
                if (k.iou(b) > iouThreshold) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(b);
            }
        }
        return kept;
    }

    @Override
    public void close() throws OrtException {
        model.close();
    }
}
