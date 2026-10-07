/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.landmarks;

import ai.onnxruntime.OrtException;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * Adds MediaPipe's 52 blendshape scores to another provider's landmarks (ADR 0002; Build Map #23, #44), from the
 * ONNX conversion of Blendshape V2 (parity with the TFLite original 1.6e-6). Blendshapes are a second vote for
 * the smile and eye detectors, never the only one: a model error fails the frame like any engine error.
 */
public final class OnnxBlendshapeLandmarker implements LandmarkProvider {

    private final LandmarkProvider landmarks;
    private final OnnxModel model;

    public OnnxBlendshapeLandmarker(LandmarkProvider landmarks, OnnxModel model) {
        this.landmarks = landmarks;
        this.model = model;
    }

    @Override
    public FaceLandmarks locate(RgbImage frame, FaceBox face) throws Exception {
        FaceLandmarks lm = landmarks.locate(frame, face);
        return lm.withBlendshapes(score(model, lm));
    }

    static float[] score(OnnxModel model, FaceLandmarks lm) throws OrtException {
        float[] out = model.run(Blendshapes.input(lm), Blendshapes.inputShape()).values();
        if (out.length != Blendshapes.COUNT) {
            throw new IllegalStateException("blendshape model returned " + out.length + " scores, expected " + Blendshapes.COUNT);
        }
        return out;
    }

    @Override
    public void close() {
        try {
            landmarks.close();
        } finally {
            try {
                model.close();
            } catch (OrtException e) {
                throw new IllegalStateException("failed to release the blendshape model", e);
            }
        }
    }
}
