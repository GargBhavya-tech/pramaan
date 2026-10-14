/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.Arrays;
import java.util.Objects;

/**
 * Which pixels of a frame are a person rather than the room (Bible Part 43). The scene and illumination cues compare
 * the live background with the stored one, so every person pixel they count as scenery is an error: it makes a
 * matching room look different, and it makes an attacker's room look like the resident's. Until this class a single
 * rectangle around the resident stood in for the person; this holds the actual shape, at the working resolution of
 * {@link SceneFingerprint} ({@value #W} x {@value #H}).
 *
 * <p>Immutable. The morphological operations return new masks.
 */
public final class PersonMask {

    public static final int W = SceneFingerprint.W;
    public static final int H = SceneFingerprint.H;

    private final boolean[] person;

    private PersonMask(boolean[] person) {
        this.person = person;
    }

    /** Nobody: every pixel is room. */
    public static PersonMask none() {
        return new PersonMask(new boolean[W * H]);
    }

    /** @param person {@code W * H} flags, row-major, true where a person is */
    public static PersonMask of(boolean[] person) {
        if (Objects.requireNonNull(person, "person").length != W * H) {
            throw new IllegalArgumentException("a mask is " + W + "x" + H + ", got " + person.length + " flags");
        }
        return new PersonMask(person.clone());
    }

    /**
     * A rectangle given in frame pixels, as {@code {x0, y0, x1, y1}}. Rounded outward exactly as
     * {@link SceneFingerprint#capture(pramaan.core.image.RgbImage, int[])} always has, so a box mask and a box
     * exclusion are the same thing.
     */
    public static PersonMask ofBox(int frameWidth, int frameHeight, int[] box) {
        boolean[] p = new boolean[W * H];
        fillBox(p, frameWidth, frameHeight, box);
        return new PersonMask(p);
    }

    private static void fillBox(boolean[] p, int frameWidth, int frameHeight, int[] box) {
        int x0 = box[0] * W / frameWidth;
        int y0 = box[1] * H / frameHeight;
        int x1 = (box[2] * W + frameWidth - 1) / frameWidth;
        int y1 = (box[3] * H + frameHeight - 1) / frameHeight;
        for (int y = Math.max(0, y0); y < Math.min(H, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(W, x1); x++) {
                p[y * W + x] = true;
            }
        }
    }

    /** True if the working-resolution pixel is a person. */
    public boolean person(int x, int y) {
        return person[y * W + x];
    }

    /** True if the pixel of a {@code frameWidth x frameHeight} frame falls on a person. */
    public boolean personAtFrame(int fx, int fy, int frameWidth, int frameHeight) {
        int x = Math.min(W - 1, fx * W / frameWidth);
        int y = Math.min(H - 1, fy * H / frameHeight);
        return person[y * W + x];
    }

    /** Room pixels as flags (a copy), the form {@link SceneFingerprint} keeps. */
    public boolean[] visible() {
        boolean[] v = new boolean[W * H];
        for (int i = 0; i < v.length; i++) {
            v[i] = !person[i];
        }
        return v;
    }

    public double personFraction() {
        int n = 0;
        for (boolean b : person) {
            n += b ? 1 : 0;
        }
        return (double) n / (W * H);
    }

    public PersonMask union(PersonMask other) {
        boolean[] p = new boolean[W * H];
        for (int i = 0; i < p.length; i++) {
            p[i] = person[i] || other.person[i];
        }
        return new PersonMask(p);
    }

    /** Also adds a frame-pixel rectangle. */
    public PersonMask withBox(int frameWidth, int frameHeight, int[] box) {
        boolean[] p = person.clone();
        fillBox(p, frameWidth, frameHeight, box);
        return new PersonMask(p);
    }

    /** Grows every person region by {@code r} pixels (square structuring element). */
    public PersonMask dilate(int r) {
        return new PersonMask(morph(person, r, true));
    }

    /** Shrinks every person region by {@code r} pixels. */
    public PersonMask erode(int r) {
        return new PersonMask(morph(person, r, false));
    }

    /** Fills holes and gaps up to about {@code 2r} pixels wide. */
    public PersonMask close(int r) {
        return dilate(r).erode(r);
    }

    /** Removes specks and bridges up to about {@code 2r} pixels wide. */
    public PersonMask open(int r) {
        return erode(r).dilate(r);
    }

    /** Drops connected person regions smaller than {@code minPixels}. */
    public PersonMask withoutSpecks(int minPixels) {
        boolean[] out = new boolean[W * H];
        boolean[] seen = new boolean[W * H];
        int[] stack = new int[W * H];
        int[] members = new int[W * H];
        for (int start = 0; start < person.length; start++) {
            if (!person[start] || seen[start]) {
                continue;
            }
            int n = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int i = stack[--top];
                members[n++] = i;
                int x = i % W;
                int y = i / W;
                if (x > 0 && person[i - 1] && !seen[i - 1]) {
                    seen[i - 1] = true;
                    stack[top++] = i - 1;
                }
                if (x < W - 1 && person[i + 1] && !seen[i + 1]) {
                    seen[i + 1] = true;
                    stack[top++] = i + 1;
                }
                if (y > 0 && person[i - W] && !seen[i - W]) {
                    seen[i - W] = true;
                    stack[top++] = i - W;
                }
                if (y < H - 1 && person[i + W] && !seen[i + W]) {
                    seen[i + W] = true;
                    stack[top++] = i + W;
                }
            }
            if (n >= minPixels) {
                for (int k = 0; k < n; k++) {
                    out[members[k]] = true;
                }
            }
        }
        return new PersonMask(out);
    }

    /** Intersection over union with another mask: 1 for identical, 0 for disjoint, 1 for two empty masks. */
    public double iou(PersonMask other) {
        int inter = 0;
        int union = 0;
        for (int i = 0; i < person.length; i++) {
            inter += person[i] && other.person[i] ? 1 : 0;
            union += person[i] || other.person[i] ? 1 : 0;
        }
        return union == 0 ? 1 : (double) inter / union;
    }

    /** Outside the frame counts as room for dilation and as person for erosion, so edges neither grow nor eat inward. */
    private static boolean[] morph(boolean[] src, int r, boolean grow) {
        boolean[] out = new boolean[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean result = !grow;
                scan:
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int px = x + dx;
                        int py = y + dy;
                        boolean v = px >= 0 && py >= 0 && px < W && py < H ? src[py * W + px] : !grow;
                        if (grow && v) {
                            result = true;
                            break scan;
                        }
                        if (!grow && !v) {
                            result = false;
                            break scan;
                        }
                    }
                }
                out[y * W + x] = result;
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PersonMask && Arrays.equals(person, ((PersonMask) o).person);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(person);
    }
}
