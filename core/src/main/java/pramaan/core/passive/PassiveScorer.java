/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.passive;

import ai.onnxruntime.OrtException;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * Passive liveness with Minivision's MiniFASNet pair (Apache-2.0), exported by tools/export_minifasnet.py
 * (Build Map #24). Preprocessing is ported line by line from the reference (generate_patches.CropImage and
 * data_io.functional.to_tensor): crop the face box scaled by 2.7 (V2) or 4.0 (V1SE), clamped and shifted
 * inside the image, resize to 80x80 bilinear, BGR, CHW, raw 0-255 (not divided by 255). Softmax over the
 * three classes, averaged over both models; class 1 is "real".
 */
public final class PassiveScorer implements AutoCloseable {

    private static final int SIZE = 80;

    private final OnnxModel v2;
    private final OnnxModel v1se;

    public PassiveScorer(OnnxModel minifasnetV2Scale27, OnnxModel minifasnetV1seScale40) {
        this.v2 = minifasnetV2Scale27;
        this.v1se = minifasnetV1seScale40;
    }

    /** Probability that the face is real, in (0, 1), before calibration. */
    public double liveProbability(RgbImage frame, FaceBox face) throws OrtException {
        double[] a = softmax(v2.run(ImageOps.toChwBgr(patch(frame, face, 2.7)), new long[] {1, 3, SIZE, SIZE}).values());
        double[] b = softmax(v1se.run(ImageOps.toChwBgr(patch(frame, face, 4.0)), new long[] {1, 3, SIZE, SIZE}).values());
        return (a[1] + b[1]) / 2;
    }

    /** CropImage._get_new_box followed by crop and resize, as in the reference implementation. */
    static RgbImage patch(RgbImage img, FaceBox face, double scale) {
        return contextCrop(img, face, scale, SIZE);
    }

    /**
     * The square context the passive model looks at — the face box scaled by {@code scale}, shifted to stay
     * inside the frame — resized to {@code size}. Evidence sample crops use it so the server re-scores the
     * same view (Build Map #61).
     */
    public static RgbImage contextCrop(RgbImage img, FaceBox face, double scale, int size) {
        int srcW = img.width();
        int srcH = img.height();
        double boxW = face.width();
        double boxH = face.height();
        double s = Math.min((srcH - 1) / boxH, Math.min((srcW - 1) / boxW, scale));
        double newW = boxW * s;
        double newH = boxH * s;
        double cx = boxW / 2 + face.x();
        double cy = boxH / 2 + face.y();
        double x0 = cx - newW / 2;
        double y0 = cy - newH / 2;
        double x1 = cx + newW / 2;
        double y1 = cy + newH / 2;
        if (x0 < 0) {
            x1 -= x0;
            x0 = 0;
        }
        if (y0 < 0) {
            y1 -= y0;
            y0 = 0;
        }
        if (x1 > srcW - 1) {
            x0 -= x1 - srcW + 1;
            x1 = srcW - 1;
        }
        if (y1 > srcH - 1) {
            y0 -= y1 - srcH + 1;
            y1 = srcH - 1;
        }
        return ImageOps.resize(ImageOps.crop(img, (int) x0, (int) y0, (int) x1, (int) y1), size, size);
    }

    static double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float l : logits) {
            max = Math.max(max, l);
        }
        double sum = 0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    @Override
    public void close() throws OrtException {
        v2.close();
        v1se.close();
    }
}
