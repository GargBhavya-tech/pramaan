/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import ai.onnxruntime.OrtException;
import java.util.Objects;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * SFace (OpenCV Zoo, Apache-2.0) face embeddings for identity continuity (Bible Part 13, lock 3; Build Map
 * #56). Input as OpenCV's {@code FaceRecognizerSF.feature}: the aligned 112x112 crop, RGB, raw 0-255, NCHW.
 * Output: 128 values, L2-normalised here. This is swap detection, not identification.
 */
public final class SFaceEmbedder implements AutoCloseable {

    public static final int DIM = 128;
    private static final long[] SHAPE = {1, 3, FaceAligner.SIZE, FaceAligner.SIZE};

    private final OnnxModel model;

    public SFaceEmbedder(OnnxModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /** Aligns with YuNet's five landmarks, then embeds. */
    public float[] embed(RgbImage frame, float[] landmarks) throws OrtException {
        return embedAligned(FaceAligner.align(frame, landmarks));
    }

    public float[] embedAligned(RgbImage aligned) throws OrtException {
        if (aligned.width() != FaceAligner.SIZE || aligned.height() != FaceAligner.SIZE) {
            throw new IllegalArgumentException("expected a " + FaceAligner.SIZE + "x" + FaceAligner.SIZE + " aligned crop");
        }
        int plane = FaceAligner.SIZE * FaceAligner.SIZE;
        float[] chw = new float[3 * plane];
        for (int y = 0; y < FaceAligner.SIZE; y++) {
            for (int x = 0; x < FaceAligner.SIZE; x++) {
                int i = y * FaceAligner.SIZE + x;
                chw[i] = aligned.get(x, y, 0);
                chw[plane + i] = aligned.get(x, y, 1);
                chw[2 * plane + i] = aligned.get(x, y, 2);
            }
        }
        float[] out = model.run(chw, SHAPE).values();
        if (out.length != DIM) {
            throw new IllegalStateException("SFace returned " + out.length + " values, expected " + DIM);
        }
        return Embeddings.normalize(out);
    }

    @Override
    public void close() throws OrtException {
        model.close();
    }
}
