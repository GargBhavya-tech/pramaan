/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.inference.OnnxModel;
import pramaan.core.passive.PassiveScorer;

/** Loads the engine's models from models/ (not committed; see docs/licences.md for provenance). */
public final class Models {

    public static final String YUNET = "face_detection_yunet_2023mar.onnx";
    public static final String MINIFAS_V2 = "minifasnet_v2_2.7_80x80.onnx";
    public static final String MINIFAS_V1SE = "minifasnet_v1se_4.0_80x80.onnx";
    public static final String SFACE = "face_recognition_sface_2021dec.onnx";

    private Models() {
    }

    public static boolean present(Path dir) {
        return Files.exists(dir.resolve(YUNET)) && Files.exists(dir.resolve(MINIFAS_V2)) && Files.exists(dir.resolve(MINIFAS_V1SE));
    }

    public static YuNetDetector detector(Path dir) throws IOException, ai.onnxruntime.OrtException {
        return new YuNetDetector(OnnxModel.load(Files.readAllBytes(dir.resolve(YUNET))), 0.8f, 0.3f);
    }

    public static PassiveScorer passive(Path dir) throws IOException, ai.onnxruntime.OrtException {
        return new PassiveScorer(OnnxModel.load(Files.readAllBytes(dir.resolve(MINIFAS_V2))),
                OnnxModel.load(Files.readAllBytes(dir.resolve(MINIFAS_V1SE))));
    }
}
