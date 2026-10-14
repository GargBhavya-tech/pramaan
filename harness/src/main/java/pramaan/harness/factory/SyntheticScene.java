/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.util.Random;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.PersonMask;

/**
 * A room with people in it, drawn so that exactly which pixels are a person is known (Bible Part 45). It exists to
 * score person segmentation: what a segmenter finds can be compared with the truth pixel for pixel. Synthetic, so
 * labelled SYNTHETIC wherever it is reported (docs/claim-sheet.md, L2): it shows how a method behaves on controlled
 * colour and exposure changes, not how it behaves on real rooms or real people.
 *
 * <p>The room is a plain wall with a floor band and scattered posters and furniture, noisy enough to give the
 * fingerprint plenty of corners. A person is a hair-capped head on a neck, shoulders and torso in a chosen clothing
 * colour. Everything is deterministic in the seed.
 */
public final class SyntheticScene {

    public static final int W = 640;
    public static final int H = 480;

    private final int[] rgb; // W*H packed 0xRRGGBB
    private final boolean[] person; // W*H truth

    private SyntheticScene(int[] rgb, boolean[] person) {
        this.rgb = rgb;
        this.person = person;
    }

    /** An empty room. */
    public static SyntheticScene room(long seed) {
        Random r = new Random(seed);
        int[] px = new int[W * H];
        int wall = pack(172 + r.nextInt(20), 162 + r.nextInt(20), 140 + r.nextInt(20));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                px[y * W + x] = y > H * 0.82 ? pack(96, 82, 70) : wall;
            }
        }
        for (int i = 0; i < 26; i++) {
            int w = 30 + r.nextInt(90);
            int h = 30 + r.nextInt(90);
            int x0 = r.nextInt(W - w);
            int y0 = r.nextInt((int) (H * 0.8) - h);
            int c = pack(30 + r.nextInt(190), 30 + r.nextInt(190), 30 + r.nextInt(190));
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    px[y * W + x] = c;
                }
            }
        }
        addNoise(px, r, 4);
        return new SyntheticScene(px, new boolean[W * H]);
    }

    /**
     * Puts a person in the room.
     *
     * @param centreX the middle of the head, as a share of the width
     * @param scale 1 is a head about a seventh of the frame wide
     * @param clothing the shirt, as {@code {r, g, b}}
     */
    public SyntheticScene withPerson(long seed, double centreX, double scale, int[] clothing) {
        Random r = new Random(seed);
        int[] px = rgb.clone();
        boolean[] truth = person.clone();
        int headW = (int) (W / 7.0 * scale);
        int headH = (int) (headW * 1.25);
        int cx = (int) (W * centreX);
        int top = (int) (H * 0.18 + (1 - scale) * H * 0.3);
        int cy = top + headH / 2;
        int skin = pack(205 + r.nextInt(15), 148 + r.nextInt(15), 118 + r.nextInt(15));
        int hair = pack(45 + r.nextInt(20), 32 + r.nextInt(14), 26 + r.nextInt(12));
        int shirt = pack(clothing[0], clothing[1], clothing[2]);
        // torso and shoulders first, so the head and neck sit on top
        int neckTop = cy + headH / 2 - headH / 8;
        int shoulderY = neckTop + headH / 3;
        for (int y = neckTop; y < H; y++) {
            int half = y < shoulderY ? headW / 4 : (int) (headW * 1.15);
            for (int x = cx - half; x <= cx + half; x++) {
                if (x >= 0 && x < W) {
                    px[y * W + x] = y < shoulderY ? skin : shirt;
                    truth[y * W + x] = true;
                }
            }
        }
        for (int y = cy - headH / 2; y <= cy + headH / 2; y++) {
            for (int x = cx - headW / 2; x <= cx + headW / 2; x++) {
                double nx = (x - cx) / (headW / 2.0);
                double ny = (y - cy) / (headH / 2.0);
                if (nx * nx + ny * ny <= 1 && x >= 0 && x < W && y >= 0 && y < H) {
                    px[y * W + x] = ny < -0.35 ? hair : skin;
                    truth[y * W + x] = true;
                }
            }
        }
        addNoise(px, r, 3);
        return new SyntheticScene(px, truth);
    }

    /** The same scene lit by a different amount: every channel scaled, as an exposure change would. */
    public SyntheticScene lit(double gain) {
        int[] px = new int[rgb.length];
        for (int i = 0; i < px.length; i++) {
            px[i] = pack(clamp(red(rgb[i]) * gain), clamp(green(rgb[i]) * gain), clamp(blue(rgb[i]) * gain));
        }
        return new SyntheticScene(px, person);
    }

    /** One poster moved and recoloured: the room has been rearranged since it was fingerprinted. */
    public SyntheticScene rearranged(long seed) {
        Random r = new Random(seed);
        int[] px = rgb.clone();
        int c = pack(200, 40, 60);
        int x0 = r.nextInt(W / 2);
        for (int y = 40; y < 200; y++) {
            for (int x = x0; x < x0 + 120; x++) {
                if (!person[y * W + x]) {
                    px[y * W + x] = c;
                }
            }
        }
        return new SyntheticScene(px, person);
    }

    public RgbImage image() {
        byte[] b = new byte[W * H * 3];
        for (int i = 0; i < rgb.length; i++) {
            b[3 * i] = (byte) red(rgb[i]);
            b[3 * i + 1] = (byte) green(rgb[i]);
            b[3 * i + 2] = (byte) blue(rgb[i]);
        }
        return RgbImage.of(W, H, b);
    }

    /** Who is in the scene, at the segmenters' working resolution; a cell is a person where most of it is. */
    public PersonMask truth() {
        boolean[] t = new boolean[PersonMask.W * PersonMask.H];
        int sx = W / PersonMask.W;
        int sy = H / PersonMask.H;
        for (int y = 0; y < PersonMask.H; y++) {
            for (int x = 0; x < PersonMask.W; x++) {
                int hits = 0;
                for (int dy = 0; dy < sy; dy++) {
                    for (int dx = 0; dx < sx; dx++) {
                        hits += person[(y * sy + dy) * W + x * sx + dx] ? 1 : 0;
                    }
                }
                t[y * PersonMask.W + x] = hits * 2 >= sx * sy;
            }
        }
        return PersonMask.of(t);
    }

    /** The head of a person drawn with these arguments, as the face box a detector would give: {x0, y0, x1, y1}. */
    public static int[] faceBoxOf(double centreX, double scale) {
        int headW = (int) (W / 7.0 * scale);
        int headH = (int) (headW * 1.25);
        int cx = (int) (W * centreX);
        int top = (int) (H * 0.18 + (1 - scale) * H * 0.3);
        return new int[] {cx - headW / 2, top, cx + headW / 2, top + headH};
    }

    private static void addNoise(int[] px, Random r, int amplitude) {
        for (int i = 0; i < px.length; i++) {
            int n = r.nextInt(2 * amplitude + 1) - amplitude;
            px[i] = pack(clamp(red(px[i]) + n), clamp(green(px[i]) + n), clamp(blue(px[i]) + n));
        }
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int pack(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int red(int p) {
        return (p >> 16) & 0xFF;
    }

    private static int green(int p) {
        return (p >> 8) & 0xFF;
    }

    private static int blue(int p) {
        return p & 0xFF;
    }
}
