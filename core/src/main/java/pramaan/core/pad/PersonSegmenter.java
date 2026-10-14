/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import pramaan.core.image.RgbImage;

/**
 * Finds the people in a frame so the scene cues can leave them out (Bible Part 43). Two implementations exist and are
 * meant to be compared: {@link BackdropSubtractionSegmenter}, which needs no model, and {@link OnnxPersonSegmenter},
 * which runs a segmentation network.
 */
public interface PersonSegmenter {

    /**
     * @param frame the live frame
     * @param faceBox {@code {x0, y0, x1, y1}} of the resident's face in frame pixels
     * @param stored the stored fingerprint of the empty room, or null; a segmenter that does not need it ignores it
     * @return who is in the frame; never null. The resident's face is always part of it.
     */
    PersonMask segment(RgbImage frame, int[] faceBox, SceneFingerprint stored);
}
