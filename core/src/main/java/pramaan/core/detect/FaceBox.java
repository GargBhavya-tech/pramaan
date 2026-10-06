/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.detect;

/**
 * A detected face in image pixels, with YuNet's five landmarks in its order: right eye, left eye, nose
 * tip, right mouth corner, left mouth corner ("right" = the subject's right, the left side of the image).
 */
public final class FaceBox {

    private final float x;
    private final float y;
    private final float w;
    private final float h;
    private final float score;
    private final float[] landmarks;

    public FaceBox(float x, float y, float w, float h, float score, float[] landmarks) {
        if (landmarks == null || landmarks.length != 10) {
            throw new IllegalArgumentException("need 5 landmarks (10 values)");
        }
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.score = score;
        this.landmarks = landmarks.clone();
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return w;
    }

    public float height() {
        return h;
    }

    public float score() {
        return score;
    }

    /** Landmark i (0-4), coordinate 0 = x, 1 = y. */
    public float landmark(int i, int coordinate) {
        return landmarks[2 * i + coordinate];
    }

    /** The five landmarks as x0, y0, ... x4, y4 (a copy): what SFace alignment takes. */
    public float[] fivePoints() {
        return landmarks.clone();
    }

    /** Inter-ocular distance in pixels: the standard face-size unit (Bible Part 6). */
    public double interOcularDistance() {
        return Math.hypot(landmark(0, 0) - landmark(1, 0), landmark(0, 1) - landmark(1, 1));
    }

    public double iou(FaceBox o) {
        double ix = Math.max(0, Math.min(x + w, o.x + o.w) - Math.max(x, o.x));
        double iy = Math.max(0, Math.min(y + h, o.y + o.h) - Math.max(y, o.y));
        double inter = ix * iy;
        double union = w * h + o.w * o.h - inter;
        return union <= 0 ? 0 : inter / union;
    }

    /** Maps a box found in a resized image back to the original: multiply by the scale factors. */
    FaceBox scaled(float sx, float sy) {
        float[] l = new float[10];
        for (int i = 0; i < 5; i++) {
            l[2 * i] = landmarks[2 * i] * sx;
            l[2 * i + 1] = landmarks[2 * i + 1] * sy;
        }
        return new FaceBox(x * sx, y * sy, w * sx, h * sy, score, l);
    }
}
