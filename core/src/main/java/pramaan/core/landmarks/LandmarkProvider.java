/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.landmarks;

import pramaan.core.detect.FaceBox;
import pramaan.core.image.RgbImage;

/**
 * Dense landmarks for a detected face (Build Map #23). Desktop uses the ONNX conversion of MediaPipe's
 * FaceMesh V2 ({@link OnnxFaceMeshLandmarker}); Android can use MediaPipe Tasks behind the same interface.
 */
public interface LandmarkProvider extends AutoCloseable {

    FaceLandmarks locate(RgbImage frame, FaceBox face) throws Exception;

    /** Releases model resources. Never throws checked exceptions, so it is safe in try-with-resources. */
    @Override
    void close();
}
