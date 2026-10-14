/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;

/** Bible Part 43: the shape of the people in a frame, and the operations segmenters clean it up with. */
class PersonMaskTest {

    private static PersonMask block(int x0, int y0, int x1, int y1) {
        boolean[] p = new boolean[PersonMask.W * PersonMask.H];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                p[y * PersonMask.W + x] = true;
            }
        }
        return PersonMask.of(p);
    }

    @Test
    void aBoxMaskExcludesExactlyWhatTheOldRectangleExclusionDid() {
        byte[] px = new byte[320 * 240 * 3];
        new Random(1).nextBytes(px);
        RgbImage scene = RgbImage.of(320, 240, px);
        int[] box = {100, 40, 220, 240};
        assertArrayEquals(SceneFingerprint.capture(scene, box).toBytes(),
                SceneFingerprint.capture(scene, PersonMask.ofBox(320, 240, box)).toBytes());
    }

    @Test
    void personAtFrameMapsFramePixelsToTheWorkingGrid() {
        PersonMask m = PersonMask.ofBox(640, 480, new int[] {320, 0, 640, 480});
        assertFalse(m.personAtFrame(10, 10, 640, 480));
        assertTrue(m.personAtFrame(600, 400, 640, 480));
        assertEquals(0.5, m.personFraction(), 0.01);
    }

    @Test
    void openingRemovesASpeckAndClosingFillsAGap() {
        PersonMask speck = block(50, 50, 52, 52); // 2 x 2
        assertEquals(0, speck.open(2).personFraction(), 0.0);
        PersonMask twoBlocks = block(20, 20, 40, 60).union(block(43, 20, 63, 60)); // 3 px apart
        assertTrue(twoBlocks.close(2).person(41, 40), "the gap between them is filled");
        assertFalse(twoBlocks.person(41, 40));
    }

    @Test
    void withoutSpecksKeepsLargeRegionsAndDropsSmallOnes() {
        PersonMask m = block(10, 10, 40, 60).union(block(100, 100, 103, 103));
        PersonMask kept = m.withoutSpecks(40);
        assertTrue(kept.person(20, 20));
        assertFalse(kept.person(101, 101));
    }

    @Test
    void erodeAndDilateAreInverseOnASolidBlockAwayFromTheEdges() {
        PersonMask b = block(30, 30, 80, 80);
        assertEquals(b, b.dilate(3).erode(3));
        assertEquals(b, b.erode(3).dilate(3));
    }

    @Test
    void iouIsOneForTheSameAndZeroForDisjointAndOneForTwoEmpty() {
        PersonMask a = block(10, 10, 30, 30);
        assertEquals(1.0, a.iou(a), 0.0);
        assertEquals(0.0, a.iou(block(100, 80, 120, 100)), 0.0);
        assertEquals(1.0, PersonMask.none().iou(PersonMask.none()), 0.0);
    }

    @Test
    void aMaskOfTheWrongSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PersonMask.of(new boolean[10]));
    }

    @Test
    void theRoomBackgroundLikelihoodIsHighForTheRoomsOwnColourAndLowForAnother() {
        // A uniform wall: its colour is common everywhere, a skin tone never occurred.
        byte[] px = new byte[320 * 240 * 3];
        for (int i = 0; i < 320 * 240; i++) {
            px[3 * i] = (byte) 170;
            px[3 * i + 1] = (byte) 160;
            px[3 * i + 2] = (byte) 140;
        }
        // a few strong corners so the fingerprint is valid
        for (int k = 0; k < 40; k++) {
            int x = 20 + (k % 8) * 35;
            int y = 20 + (k / 8) * 40;
            for (int dy = 0; dy < 14; dy++) {
                for (int dx = 0; dx < 14; dx++) {
                    px[((y + dy) * 320 + x + dx) * 3] = (byte) 40;
                }
            }
        }
        SceneFingerprint fp = SceneFingerprint.capture(RgbImage.of(320, 240, px));
        assertTrue(fp.backgroundLikelihood(80, 60, 170, 160, 140) > 0.3);
        assertTrue(fp.backgroundLikelihood(80, 60, 205, 150, 120) < 0.01);
        double[] mean = fp.meanColour();
        assertTrue(mean[0] > 150 && mean[0] < 180, "mean red " + mean[0]);
    }
}
