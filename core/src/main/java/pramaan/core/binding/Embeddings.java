/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.util.Arrays;
import java.util.List;

/** Small vector helpers for face embeddings. */
public final class Embeddings {

    private Embeddings() {
    }

    public static float[] normalize(float[] v) {
        double s = 0;
        for (float x : v) {
            s += x * x;
        }
        double n = Math.sqrt(s);
        if (n == 0) {
            throw new IllegalArgumentException("zero embedding");
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("embedding sizes differ: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }

    /** Element-wise median, normalised: robust to a few frames with a bad crop or a blink. */
    public static float[] median(List<float[]> vs) {
        if (vs.isEmpty()) {
            throw new IllegalArgumentException("no embeddings");
        }
        int d = vs.get(0).length;
        float[] out = new float[d];
        float[] col = new float[vs.size()];
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < vs.size(); i++) {
                col[i] = vs.get(i)[j];
            }
            Arrays.sort(col);
            int n = col.length;
            out[j] = n % 2 == 1 ? col[n / 2] : (col[n / 2 - 1] + col[n / 2]) / 2;
        }
        return normalize(out);
    }
}
