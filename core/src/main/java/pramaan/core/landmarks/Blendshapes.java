/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.landmarks;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * MediaPipe Blendshape V2 (Apache-2.0) input and output conventions (ADR 0002; Build Map #23, #44).
 *
 * <p>{@link #SUBSET} and {@link #NAMES} are copied verbatim from MediaPipe's
 * {@code mediapipe/tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc} ({@code kLandmarksSubsetIdxs},
 * {@code kBlendshapeNames}). The model takes those 146 landmarks as a {@code [1, 146, 2]} tensor of x, y in the
 * image's pixels ({@code LandmarksToTensorCalculator} scales MediaPipe's normalised x by width and y by height) —
 * which is what {@link FaceLandmarks} already holds — and returns 52 scores in [0, 1].
 */
public final class Blendshapes {

    /** The 146 of the 478 landmarks the model reads, in MediaPipe's order. */
    static final int[] SUBSET = {
        0, 1, 4, 5, 6, 7, 8, 10, 13, 14, 17, 21, 33, 37, 39,
        40, 46, 52, 53, 54, 55, 58, 61, 63, 65, 66, 67, 70, 78, 80,
        81, 82, 84, 87, 88, 91, 93, 95, 103, 105, 107, 109, 127, 132, 133,
        136, 144, 145, 146, 148, 149, 150, 152, 153, 154, 155, 157, 158, 159, 160,
        161, 162, 163, 168, 172, 173, 176, 178, 181, 185, 191, 195, 197, 234, 246,
        249, 251, 263, 267, 269, 270, 276, 282, 283, 284, 285, 288, 291, 293, 295,
        296, 297, 300, 308, 310, 311, 312, 314, 317, 318, 321, 323, 324, 332, 334,
        336, 338, 356, 361, 362, 365, 373, 374, 375, 377, 378, 379, 380, 381, 382,
        384, 385, 386, 387, 388, 389, 390, 397, 398, 400, 402, 405, 409, 415, 454,
        466, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477};

    /**
     * The 52 outputs, in the model's order. Which side "Left" names is not relied on anywhere: the detectors use the
     * left and right scores together (both, or either), never one side against a landmark side.
     */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
            "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft",
            "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft", "eyeLookOutRight", "eyeLookUpLeft",
            "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft",
            "jawOpen", "jawRight", "mouthClose", "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft",
            "mouthFrownRight", "mouthFunnel", "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft",
            "mouthPressRight", "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower",
            "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
            "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight"));

    public static final int COUNT = 52;
    public static final int EYE_BLINK_LEFT = NAMES.indexOf("eyeBlinkLeft");
    public static final int EYE_BLINK_RIGHT = NAMES.indexOf("eyeBlinkRight");
    public static final int MOUTH_SMILE_LEFT = NAMES.indexOf("mouthSmileLeft");
    public static final int MOUTH_SMILE_RIGHT = NAMES.indexOf("mouthSmileRight");

    private Blendshapes() {
    }

    /** The model input for these landmarks: 146 (x, y) pairs in frame pixels, shape {@code [1, 146, 2]}. */
    public static float[] input(FaceLandmarks lm) {
        float[] out = new float[SUBSET.length * 2];
        for (int i = 0; i < SUBSET.length; i++) {
            out[2 * i] = lm.x(SUBSET[i]);
            out[2 * i + 1] = lm.y(SUBSET[i]);
        }
        return out;
    }

    public static long[] inputShape() {
        return new long[] {1, SUBSET.length, 2};
    }
}
