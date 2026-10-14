/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.SceneFingerprint;

/** Build Map #87: the station's stored backdrop, its file format, and the three cues it switches on. */
class SceneContextTest {

    private static final int W = 320;
    private static final int H = 240;

    private static byte[] room(long seed) {
        Random r = new Random(seed);
        byte[] px = new byte[W * H * 3];
        for (int i = 0; i < W * H; i++) {
            for (int c = 0; c < 3; c++) {
                px[3 * i + c] = (byte) (110 + (i % W) / 12 + (i / W) / 16);
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

    /** The room with a skin-coloured block where the resident stands (the box the detector would report). */
    private static RgbImage withFace(byte[] roomPx) {
        byte[] px = roomPx.clone();
        for (int y = 40; y < 200; y++) {
            for (int x = 120; x < 200; x++) {
                px[(y * W + x) * 3] = (byte) 200;
                px[(y * W + x) * 3 + 1] = (byte) 150;
                px[(y * W + x) * 3 + 2] = (byte) 130;
            }
        }
        return RgbImage.of(W, H, px);
    }

    private static final FaceBox FACE = new FaceBox(120, 40, 80, 160, 0.99f, new float[10]);

    @Test
    void aFingerprintSurvivesItsFileFormat() {
        SceneFingerprint original = SceneFingerprint.capture(RgbImage.of(W, H, room(1)));
        SceneFingerprint copy = SceneFingerprint.fromBytes(original.toBytes());
        assertEquals(original.keypointCount(), copy.keypointCount());
        assertEquals(original.visibleFraction(), copy.visibleFraction(), 1e-12);
        SceneFingerprint.Comparison same = original.compare(copy);
        assertTrue(same.score(0.5) > 0.95, "a fingerprint matches its own copy: " + same.score(0.5));
        assertEquals(original.toBytes().length, copy.toBytes().length);
    }

    @Test
    void bytesThatAreNotAFingerprintAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SceneFingerprint.fromBytes(new byte[] {1, 2, 3}));
        byte[] good = SceneFingerprint.capture(RgbImage.of(W, H, room(1))).toBytes();
        byte[] bad = good.clone();
        bad[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SceneFingerprint.fromBytes(bad));
        assertThrows(IllegalArgumentException.class,
                () -> SceneFingerprint.fromBytes(java.util.Arrays.copyOf(good, good.length / 2)));
    }

    @Test
    void theFirstFrameIsEvaluatedAndThenOneInFive() {
        SceneContext ctx = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(1))));
        RgbImage frame = withFace(room(1));
        RgbImage face = ImageOps.crop(frame, 120, 40, 199, 199);
        int evaluated = 0;
        for (int i = 0; i < 10; i++) {
            if (!ctx.evaluate(frame, FACE, face).isEmpty()) {
                evaluated++;
            }
        }
        assertEquals(2, evaluated, "frames 0 and 5 of 10");
    }

    @Test
    void theSameRoomVotesForLifeAndAnotherRoomDoesNot() {
        RgbImage faceFrame = withFace(room(1));
        RgbImage face = ImageOps.crop(faceFrame, 120, 40, 199, 199);
        SceneContext same = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(1))));
        List<CueResult> inRoom = same.evaluate(faceFrame, FACE, face);
        CueResult scene = byName(inRoom, "scene");
        assertNotNull(scene);
        assertFalse(scene.abstained());
        assertTrue(scene.pLive() > 0.8, "p " + scene.pLive());
        assertFalse(same.cameraMoved());

        SceneContext other = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(2))));
        CueResult elsewhere = byName(other.evaluate(faceFrame, FACE, face), "scene");
        assertTrue(elsewhere.pLive() < 0.3, "p " + elsewhere.pLive());
    }

    @Test
    void allThreeCuesAreReportedEvenWhenTheyAbstain() {
        SceneContext ctx = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(1))));
        RgbImage frame = withFace(room(1));
        List<CueResult> out = ctx.evaluate(frame, FACE, ImageOps.crop(frame, 120, 40, 199, 199));
        assertEquals(3, out.size());
        assertNotNull(byName(out, "scene"));
        assertNotNull(byName(out, "whitePoint"));
        assertNotNull(byName(out, "lightDirection"));
    }

    @Test
    void withASegmenterTheCuesLeaveOutThePeopleItFindsAndTheSameRoomStillVotesForLife() {
        RgbImage faceFrame = withFace(room(1));
        RgbImage face = ImageOps.crop(faceFrame, 120, 40, 199, 199);
        SceneContext ctx = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(1))),
                new pramaan.core.pad.BackdropSubtractionSegmenter());
        assertNull(ctx.lastPeople(), "nothing evaluated yet");
        List<CueResult> out = ctx.evaluate(faceFrame, FACE, face);
        assertEquals(3, out.size());
        assertNotNull(ctx.lastPeople());
        assertTrue(ctx.lastPeople().personFraction() > 0.05, "the resident's block is found");
        assertTrue(ctx.lastPeople().personFraction() < 0.6, "and not the whole room");
        CueResult scene = byName(out, "scene");
        assertFalse(scene.abstained());
        assertTrue(scene.pLive() > 0.8, "p " + scene.pLive());
    }

    /** The frame with every channel multiplied by {@code gain}: an exposure change. */
    private static RgbImage lit(RgbImage img, double gain) {
        byte[] px = new byte[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                for (int c = 0; c < 3; c++) {
                    px[(y * W + x) * 3 + c] = (byte) Math.max(0, Math.min(255, Math.round(img.get(x, y, c) * gain)));
                }
            }
        }
        return RgbImage.of(W, H, px);
    }

    /** Found by the station self-test: the scene cue used to score the same room 0.35 after a 15% exposure change. */
    @Test
    void theSameRoomStillVotesForLifeAfterAnExposureChangeInEitherDirection() {
        for (double gain : new double[] {0.75, 0.85, 1.15, 1.25}) {
            RgbImage frame = lit(withFace(room(1)), gain);
            SceneContext ctx = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(1))));
            CueResult scene = byName(ctx.evaluate(frame, FACE, ImageOps.crop(frame, 120, 40, 199, 199)), "scene");
            assertFalse(scene.abstained(), "gain " + gain);
            assertTrue(scene.pLive() > 0.8, "gain " + gain + " p " + scene.pLive());
        }
    }

    @Test
    void exposureMatchingDoesNotLetAnotherRoomThroughAtAnyBrightness() {
        for (double gain : new double[] {0.75, 1.25}) {
            RgbImage frame = lit(withFace(room(1)), gain);
            SceneContext other = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(2))));
            CueResult scene = byName(other.evaluate(frame, FACE, ImageOps.crop(frame, 120, 40, 199, 199)), "scene");
            assertTrue(scene.pLive() < 0.3, "gain " + gain + " p " + scene.pLive());
        }
    }

    @Test
    void withASegmenterAnotherRoomStillDoesNotVoteForLife() {
        RgbImage faceFrame = withFace(room(1));
        RgbImage face = ImageOps.crop(faceFrame, 120, 40, 199, 199);
        SceneContext other = new SceneContext(SceneFingerprint.capture(RgbImage.of(W, H, room(2))),
                new pramaan.core.pad.BackdropSubtractionSegmenter());
        CueResult elsewhere = byName(other.evaluate(faceFrame, FACE, face), "scene");
        assertTrue(elsewhere.pLive() < 0.3, "p " + elsewhere.pLive());
    }

    @Test
    void aFaceTooCloseToTheEdgeLeavesNoBackdropToJudgeTheLight() {
        assertNull(SceneContext.backdrop(withFace(room(1)), 10, W - 10));
        assertNotNull(SceneContext.backdrop(withFace(room(1)), 100, 220));
    }

    private static CueResult byName(List<CueResult> results, String name) {
        return results.stream().filter(r -> r.cue().equals(name)).findFirst().orElse(null);
    }
}
