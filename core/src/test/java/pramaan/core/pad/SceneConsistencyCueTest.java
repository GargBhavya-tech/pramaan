/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;

/** Bible Part 43 A and Build Map #87: SceneFingerprintTest (same room vs different room clips). */
class SceneConsistencyCueTest {

    private static final int W = 320;
    private static final int H = 240;
    /** The resident stands in the middle third. */
    private static final int[] RESIDENT = {100, 30, 220, 240};

    /** A procedural room: a wall gradient with many coloured rectangles. Same seed, same room. */
    private static byte[] room(long seed) {
        Random r = new Random(seed);
        byte[] px = new byte[W * H * 3];
        int[] base = {90 + r.nextInt(80), 90 + r.nextInt(80), 90 + r.nextInt(80)};
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                for (int c = 0; c < 3; c++) {
                    px[(y * W + x) * 3 + c] = (byte) (base[c] + (x - W / 2) / 12 + (y - H / 2) / 16);
                }
            }
        }
        for (int i = 0; i < 70; i++) {
            int rw = 10 + r.nextInt(50);
            int rh = 10 + r.nextInt(50);
            int rx = r.nextInt(W - rw);
            int ry = r.nextInt(H - rh);
            int[] col = {r.nextInt(256), r.nextInt(256), r.nextInt(256)};
            for (int y = ry; y < ry + rh; y++) {
                for (int x = rx; x < rx + rw; x++) {
                    for (int c = 0; c < 3; c++) {
                        px[(y * W + x) * 3 + c] = (byte) col[c];
                    }
                }
            }
        }
        return px;
    }

    /** Draw a plain "resident" over the middle of a room, add sensor noise and a small global brightness change. */
    private static RgbImage withResident(byte[] roomPx, long noiseSeed, int brightness) {
        byte[] px = roomPx.clone();
        Random n = new Random(noiseSeed);
        for (int i = 0; i < px.length; i++) {
            int v = (px[i] & 0xFF) + brightness + (int) Math.round(n.nextGaussian() * 2);
            px[i] = (byte) Math.max(0, Math.min(255, v));
        }
        for (int y = RESIDENT[1]; y < RESIDENT[3]; y++) {
            for (int x = RESIDENT[0]; x < RESIDENT[2]; x++) {
                px[(y * W + x) * 3] = (byte) 200;
                px[(y * W + x) * 3 + 1] = (byte) 150;
                px[(y * W + x) * 3 + 2] = (byte) 130;
            }
        }
        return RgbImage.of(W, H, px);
    }

    private static RgbImage shifted(byte[] roomPx, int dx, int dy) {
        byte[] px = new byte[roomPx.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int sx = Math.max(0, Math.min(W - 1, x - dx));
                int sy = Math.max(0, Math.min(H - 1, y - dy));
                for (int c = 0; c < 3; c++) {
                    px[(y * W + x) * 3 + c] = roomPx[(sy * W + sx) * 3 + c];
                }
            }
        }
        return RgbImage.of(W, H, px);
    }

    private static final byte[] ROOM_A = room(101);
    private static final byte[] ROOM_B = room(202);
    private static final SceneFingerprint STORED = SceneFingerprint.capture(RgbImage.of(W, H, ROOM_A));

    @Test
    void theEmptyRoomYieldsPlentyOfKeypoints() {
        assertTrue(STORED.keypointCount() >= 60, "keypoints " + STORED.keypointCount());
    }

    @Test
    void theSameRoomWithAResidentInItScoresHigh() {
        SceneConsistencyCue.Analysis a = new SceneConsistencyCue().analyse(STORED, withResident(ROOM_A, 1, 6), RESIDENT);
        assertFalse(a.result.abstained());
        assertTrue(a.score > 0.65, "S_bg " + a.score + " histCorr " + a.comparison.histCorr + " ratio "
                + a.comparison.matchRatio);
        assertTrue(a.result.pLive() > 0.8, "p " + a.result.pLive());
        assertFalse(a.cameraMoved);
    }

    @Test
    void aClipRecordedInAnotherRoomScoresLow() {
        SceneConsistencyCue.Analysis a = new SceneConsistencyCue().analyse(STORED, withResident(ROOM_B, 2, 0), RESIDENT);
        assertFalse(a.result.abstained());
        assertTrue(a.score < 0.35, "S_bg " + a.score);
        assertTrue(a.result.pLive() < 0.2, "p " + a.result.pLive());
    }

    @Test
    void theSameRoomAgainstTheWrongRoomIsFarBetter() {
        SceneConsistencyCue cue = new SceneConsistencyCue();
        double same = cue.analyse(STORED, withResident(ROOM_A, 3, 0), RESIDENT).score;
        double other = cue.analyse(STORED, withResident(ROOM_B, 3, 0), RESIDENT).score;
        assertTrue(same - other > 0.4, same + " vs " + other);
    }

    @Test
    void aBumpedCameraIsReportedForReFingerprintingNotRejected() {
        RgbImage moved = shifted(ROOM_A, 40, 24); // 20 and 12 pixels at the 160x120 working resolution
        SceneConsistencyCue.Analysis a = new SceneConsistencyCue().analyse(STORED, moved, new int[] {0, 0, 1, 1});
        assertTrue(a.cameraMoved, "median shift " + (a.comparison == null ? -1 : a.comparison.medianShift));
        assertTrue(a.result.abstained());
        assertEquals(CueResult.AbstainReason.NOT_APPLICABLE, a.result.abstainReason());
    }

    @Test
    void noFingerprintMeansAbstain() {
        CueResult r = new SceneConsistencyCue().evaluate(null, withResident(ROOM_A, 4, 0), RESIDENT);
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, r.abstainReason());
    }

    @Test
    void aBlankWallMakesAnUselessFingerprint() {
        byte[] flat = new byte[W * H * 3];
        java.util.Arrays.fill(flat, (byte) 120);
        SceneFingerprint blank = SceneFingerprint.capture(RgbImage.of(W, H, flat));
        CueResult r = new SceneConsistencyCue().evaluate(blank, withResident(ROOM_A, 5, 0), RESIDENT);
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, r.abstainReason());
    }

    @Test
    void aResidentFillingTheFrameLeavesTooLittleBackgroundToJudge() {
        CueResult r = new SceneConsistencyCue().evaluate(STORED, withResident(ROOM_A, 6, 0), new int[] {0, 0, W, H});
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL, r.abstainReason());
    }
}
