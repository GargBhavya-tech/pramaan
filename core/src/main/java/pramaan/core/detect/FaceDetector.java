/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.detect;

import ai.onnxruntime.OrtException;
import java.util.List;
import pramaan.core.image.RgbImage;

/**
 * A detector that locates faces within an image.
 */
public interface FaceDetector {

    /** Locate faces in the image's coordinate system, primary / highest score first. */
    List<FaceBox> detect(RgbImage image) throws OrtException;
}
