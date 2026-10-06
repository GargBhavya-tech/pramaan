/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pramaan.core.detect.FaceBox;
import pramaan.core.gate.FaceGate;
import pramaan.core.image.RgbImage;

/** Build Map #21, #22: Q = min, each synthetic defect drives its own component down, and gets its own guidance. */
class QualityAndGuidanceTest {

    private static final int W = 320;
    private static final int H = 240;

    /** A textured face-like patch on a background of the given brightness. */
    private static RgbImage scene(int background, int faceLevel, boolean texture, boolean halfShadow) {
        byte[] rgb = new byte[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = background;
                if (x >= 100 && x < 220 && y >= 60 && y < 200) {
                    v = faceLevel + (texture ? ((x / 3 + y / 3) % 2 == 0 ? 25 : -25) : 0);
                    if (halfShadow && x >= 160) {
                        v -= 60;
                    }
                }
                v = Math.max(0, Math.min(255, v));
                int i = (y * W + x) * 3;
                rgb[i] = (byte) v;
                rgb[i + 1] = (byte) v;
                rgb[i + 2] = (byte) v;
            }
        }
        return RgbImage.of(W, H, rgb);
    }

    private static FaceBox box(float iod, float noseDx) {
        float cx = 160;
        return new FaceBox(100, 60, 120, 140, 0.95f, new float[] {cx - iod / 2, 110, cx + iod / 2, 110, cx + noseDx, 140,
            cx - iod / 3, 165, cx + iod / 3, 165});
    }

    private final QualityAssessor assessor = new QualityAssessor();

    @Test
    void aGoodFacePasses() {
        QualityReport r = assessor.assess(scene(120, 130, true, false), box(70, 0), false);
        assertTrue(r.passes(), "q=" + r.q() + " scores=" + r.scores());
        assertEquals(r.q(), r.scores().values().stream().mapToDouble(Double::doubleValue).min().orElse(1), 1e-12);
    }

    @Test
    void eachDefectFailsItsOwnComponent() {
        assertEquals(QualityComponent.SIZE, assessor.assess(scene(120, 130, true, false), box(30, 0), false).worst());
        assertEquals(QualityComponent.POSE, assessor.assess(scene(120, 130, true, false), box(70, 30), false).worst());
        assertEquals(QualityComponent.SHARPNESS, assessor.assess(scene(120, 130, false, false), box(70, 0), false).worst());
        assertEquals(QualityComponent.UNIFORMITY, assessor.assess(scene(120, 150, true, true), box(70, 0), false).worst());
        QualityReport backlit = assessor.assess(scene(250, 70, true, false), box(70, 0), false);
        assertFalse(backlit.passes());
        assertEquals(QualityComponent.BACKLIGHT, backlit.worst());
    }

    @Test
    void theMinimumIsNotHiddenByGoodComponents() {
        QualityReport r = assessor.assess(scene(120, 130, true, false), box(30, 0), false);
        assertFalse(r.passes());
        assertTrue(r.scores().get(QualityComponent.SHARPNESS) > 0.5, "other components are still good");
    }

    @Test
    void guidanceIsActionableAndNeverAVerdict() {
        assertEquals(Guidance.MOVE_CLOSER,
                Guidance.forQuality(assessor.assess(scene(120, 130, true, false), box(30, 0), false), 0));
        assertEquals(Guidance.MOVE_AWAY_FROM_LIGHT_BEHIND_YOU,
                Guidance.forQuality(assessor.assess(scene(250, 70, true, false), box(70, 0), false), -1));
        assertEquals(Guidance.ONLY_ONE_PERSON, Guidance.forGate(FaceGate.Status.MULTIPLE_FACES));
        assertEquals(Guidance.LOOK_AT_THE_CAMERA, Guidance.forGate(FaceGate.Status.NO_FACE));
    }
}
