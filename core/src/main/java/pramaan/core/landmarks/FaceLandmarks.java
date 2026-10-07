/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.landmarks;

/**
 * MediaPipe FaceMesh V2 landmarks (478 points: 468 face + 10 iris) in frame pixels, plus the model's face
 * presence probability. Index constants follow MediaPipe's canonical mesh topology.
 */
public final class FaceLandmarks {

    public static final int COUNT = 478;
    public static final int NOSE_TIP = 1;
    public static final int UPPER_LIP = 13;
    public static final int LOWER_LIP = 14;
    public static final int MOUTH_RIGHT = 61;
    public static final int MOUTH_LEFT = 291;
    public static final int RIGHT_IRIS = 468;
    public static final int LEFT_IRIS = 473;

    /** Eye-aspect-ratio points p1..p6 (corners p1/p4, upper lid p2/p3, lower lid p6/p5), subject's right eye. */
    public static final int[] RIGHT_EYE_EAR = {33, 160, 158, 133, 153, 144};
    /** Same for the subject's left eye. */
    public static final int[] LEFT_EYE_EAR = {362, 385, 387, 263, 373, 380};

    private final float[] xyz;
    private final double presence;
    private final float[] blendshapes;

    public FaceLandmarks(float[] xyz, double presence) {
        this(xyz, presence, null);
    }

    private FaceLandmarks(float[] xyz, double presence, float[] blendshapes) {
        if (xyz == null || xyz.length != COUNT * 3) {
            throw new IllegalArgumentException("need " + COUNT * 3 + " values");
        }
        if (blendshapes != null && blendshapes.length != Blendshapes.COUNT) {
            throw new IllegalArgumentException("need " + Blendshapes.COUNT + " blendshape scores");
        }
        this.xyz = xyz.clone();
        this.presence = presence;
        this.blendshapes = blendshapes == null ? null : blendshapes.clone();
    }

    /** These landmarks with the 52 blendshape scores in {@link Blendshapes#NAMES} order. */
    public FaceLandmarks withBlendshapes(float[] scores) {
        return new FaceLandmarks(xyz, presence, scores);
    }

    /** Whether blendshape scores are present: a platform without the model is not a failure (ADR 0002). */
    public boolean hasBlendshapes() {
        return blendshapes != null;
    }

    /** One score by index into {@link Blendshapes#NAMES}; NaN when no blendshapes were computed. */
    public double blendshape(int index) {
        return blendshapes == null ? Double.NaN : blendshapes[index];
    }

    public float x(int i) {
        return xyz[3 * i];
    }

    public float y(int i) {
        return xyz[3 * i + 1];
    }

    /** Relative depth, in the same pixel scale as x (smaller is closer to the camera). */
    public float z(int i) {
        return xyz[3 * i + 2];
    }

    /** Probability that the crop contains a face. */
    public double presence() {
        return presence;
    }

    /** EAR = (|p2-p6| + |p3-p5|) / (2 |p1-p4|): about 0.25-0.35 open, collapses when the eye closes (Bible Part 11.1). */
    public double eyeAspectRatio(boolean rightEye) {
        int[] p = rightEye ? RIGHT_EYE_EAR : LEFT_EYE_EAR;
        return (dist(p[1], p[5]) + dist(p[2], p[4])) / (2 * dist(p[0], p[3]));
    }

    public double dist(int a, int b) {
        return Math.hypot(x(a) - x(b), y(a) - y(b));
    }
}
