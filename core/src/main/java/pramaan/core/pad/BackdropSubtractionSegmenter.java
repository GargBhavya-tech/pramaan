/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * Route A, no model (Bible Part 43): a person is whatever in the frame does not look like the stored room. The stored
 * fingerprint keeps, per cell of an 8 x 6 grid, how often each colour occurred in the empty room (never a picture), so
 * a pixel can be asked "how common is your colour at this place in the room?" ({@link
 * SceneFingerprint#backgroundLikelihood}). A colour the room never had there is not room. That catches the resident's
 * skin, hair, and most clothes, and also a second person walking behind, which a rectangle round the resident never
 * could.
 *
 * <p>The steps, in order:
 * <ol>
 *   <li><b>exposure:</b> the live frame is matched to the stored room's average colour (gain clamped to 0.7-1.4,
 *       measured outside the face), so a brighter afternoon is not a roomful of people;</li>
 *   <li><b>test:</b> a pixel is foreground if its likelihood is below {@value #LIKELIHOOD_FLOOR}; cells the stored room
 *       barely saw are undecided and count as room;</li>
 *   <li><b>clean-up:</b> specks removed, small gaps closed, regions under {@value #MIN_REGION} pixels dropped;</li>
 *   <li><b>priors:</b> the face (grown by 15%) and a narrow column from the face down to the bottom of the frame are
 *       always person, because the neck and torso are there whatever colour the clothes are.</li>
 * </ol>
 *
 * <p>Honest limits. Clothes the colour of the wall are missed except in the column under the face: the method cannot
 * see a person who matches the room, and a model can. A change of lighting bigger than the exposure gain, or a room
 * that has been rearranged since the backdrop was taken, makes it over-report people, which only costs background,
 * never accepts anyone. The threshold is provisional, set on synthetic scenes (docs/person-segmentation.md).
 */
public final class BackdropSubtractionSegmenter implements PersonSegmenter {

    /** Below this share of the cell's pixels sharing a pixel's colour, the pixel is not room. */
    public static final double LIKELIHOOD_FLOOR = 0.012;
    public static final int MIN_REGION = 40;
    private static final double MIN_GAIN = 0.7;
    private static final double MAX_GAIN = 1.4;
    private static final double FACE_GROWTH = 0.15;
    private static final double TORSO_COLUMN = 0.3;

    @Override
    public PersonMask segment(RgbImage frame, int[] faceBox, SceneFingerprint stored) {
        PersonMask prior = prior(frame, faceBox);
        if (stored == null) {
            return prior;
        }
        RgbImage small = ImageOps.resize(frame, PersonMask.W, PersonMask.H);
        PersonMask face = PersonMask.ofBox(frame.width(), frame.height(), grown(faceBox, FACE_GROWTH, frame));
        double[] gain = gain(small, face, stored);
        boolean[] foreground = new boolean[PersonMask.W * PersonMask.H];
        for (int y = 0; y < PersonMask.H; y++) {
            for (int x = 0; x < PersonMask.W; x++) {
                int[] level = new int[3];
                for (int c = 0; c < 3; c++) {
                    level[c] = (int) Math.max(0, Math.min(255, Math.round(small.get(x, y, c) * gain[c])));
                }
                double like = stored.backgroundLikelihood(x, y, level[0], level[1], level[2]);
                foreground[y * PersonMask.W + x] = !Double.isNaN(like) && like < LIKELIHOOD_FLOOR;
            }
        }
        PersonMask subtracted = PersonMask.of(foreground).open(1).close(2).withoutSpecks(MIN_REGION);
        return subtracted.union(prior);
    }

    /** The face and the column below it: person whatever the clothes look like. */
    static PersonMask prior(RgbImage frame, int[] faceBox) {
        int w = faceBox[2] - faceBox[0];
        int[] column = {Math.max(0, faceBox[0] - (int) (TORSO_COLUMN * w)), faceBox[1],
            Math.min(frame.width(), faceBox[2] + (int) (TORSO_COLUMN * w)), frame.height()};
        return PersonMask.ofBox(frame.width(), frame.height(), grown(faceBox, FACE_GROWTH, frame))
                .withBox(frame.width(), frame.height(), column);
    }

    private static int[] grown(int[] box, double share, RgbImage frame) {
        int dx = (int) ((box[2] - box[0]) * share);
        int dy = (int) ((box[3] - box[1]) * share);
        return new int[] {Math.max(0, box[0] - dx), Math.max(0, box[1] - dy), Math.min(frame.width(), box[2] + dx),
            Math.min(frame.height(), box[3] + dy)};
    }

    private static double[] gain(RgbImage small, PersonMask face, SceneFingerprint stored) {
        double g = stored.exposureGain(small, face);
        return new double[] {g, g, g};
    }
}
