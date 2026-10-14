/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;

/** Bible Part 43 B.2 and Build Map #87: LightDirectionTest on synthetic shading with a known light. */
class LightDirectionConsistencyCueTest {

    /**
     * Luminance 128 plus a linear ramp. {@code gx} and {@code gy} are the change across the full width and height,
     * as a fraction of the mean: positive gx is brighter to the right.
     */
    private static RgbImage ramp(int w, int h, double gx, double gy) {
        byte[] px = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 128 * (1 + gx * ((x + 0.5) / w - 0.5) + gy * ((y + 0.5) / h - 0.5));
                byte b = (byte) Math.max(0, Math.min(255, Math.round(v)));
                int i = (y * w + x) * 3;
                px[i] = b;
                px[i + 1] = b;
                px[i + 2] = b;
            }
        }
        return RgbImage.of(w, h, px);
    }

    @Test
    void theGradientIsRecoveredAsARelativeChangeAcrossTheRegion() {
        double[] g = LightDirectionConsistencyCue.gradient(ramp(40, 40, 0.4, -0.2));
        assertEquals(0.4, g[0], 0.02);
        assertEquals(-0.2, g[1], 0.02);
    }

    @Test
    void aFaceLitFromTheSameSideAsTheRoomAgrees() {
        CueResult r = new LightDirectionConsistencyCue().evaluate(ramp(40, 40, -0.3, 0.05), ramp(60, 40, -0.25, 0.03));
        assertTrue(!r.abstained());
        assertTrue(r.rawValue() > 0.9, "cosine " + r.rawValue());
        assertTrue(r.pLive() > 0.9, "p " + r.pLive());
    }

    @Test
    void aReplayLitFromTheOppositeSideDisagrees() {
        CueResult r = new LightDirectionConsistencyCue().evaluate(ramp(40, 40, 0.3, 0), ramp(60, 40, -0.25, 0));
        assertTrue(r.rawValue() < -0.9, "cosine " + r.rawValue());
        assertTrue(r.pLive() < 0.1, "p " + r.pLive());
    }

    @Test
    void lightFromAboveAgainstLightFromTheSideIsUndecided() {
        CueResult r = new LightDirectionConsistencyCue().evaluate(ramp(40, 40, 0, 0.3), ramp(60, 40, 0.25, 0));
        assertEquals(0.5, r.pLive(), 0.05);
    }

    @Test
    void evenDiffuseLightHasNoDirectionAndAbstains() {
        CueResult r = new LightDirectionConsistencyCue().evaluate(ramp(40, 40, 0.01, 0), ramp(60, 40, -0.25, 0));
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.NOT_APPLICABLE, r.abstainReason());
    }

    @Test
    void noBackdropOrATinyCropAbstains() {
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING,
                new LightDirectionConsistencyCue().evaluate(ramp(40, 40, 0.3, 0), null).abstainReason());
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL,
                new LightDirectionConsistencyCue().evaluate(ramp(5, 5, 0.3, 0), ramp(60, 40, 0.3, 0)).abstainReason());
    }
}
