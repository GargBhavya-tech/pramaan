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

/** Bible Part 43.B.1; Build Map #87: the white-point-consistency cue's worked example. */
class WhitePointConsistencyCueTest {

    private static RgbImage solid(int w, int h, int r, int g, int b) {
        byte[] px = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            px[3 * i] = (byte) r;
            px[3 * i + 1] = (byte) g;
            px[3 * i + 2] = (byte) b;
        }
        return RgbImage.of(w, h, px);
    }

    // Chosen so r = R/(R+G+B), g = G/(R+G+B) land on the Bible's own worked-example chromaticities.
    private static final RgbImage BACKDROP = solid(20, 20, 102, 92, 61); // (r,g) = (0.40, 0.36)
    private static final RgbImage LIVE_HIGHLIGHT = solid(20, 20, 105, 92, 58); // (0.41, 0.36)
    private static final RgbImage REPLAY_HIGHLIGHT = solid(20, 20, 79, 87, 89); // (0.31, 0.34)

    @Test
    void backdropChromaticityMatchesTheWorkedExample() {
        double[] rg = WhitePointConsistencyCue.chromaticity(BACKDROP);
        assertEquals(0.40, rg[0], 0.005);
        assertEquals(0.36, rg[1], 0.005);
    }

    @Test
    void aLiveFacesHighlightAgreesWithTheRoomAndPasses() {
        CueResult r = new WhitePointConsistencyCue().evaluate(BACKDROP, LIVE_HIGHLIGHT);
        assertTrue(!r.abstained());
        assertEquals(0.01, r.rawValue(), 0.005, "delta ~= the Bible's 0.01");
        assertTrue(r.pLive() > 0.5, "delta well under the threshold: high P(live)");
    }

    @Test
    void aPhoneReplaysBluishHighlightDisagreesWithTheRoomAndIsSuspicious() {
        CueResult r = new WhitePointConsistencyCue().evaluate(BACKDROP, REPLAY_HIGHLIGHT);
        assertTrue(!r.abstained());
        assertEquals(0.09, r.rawValue(), 0.005, "delta ~= the Bible's 0.09");
        assertTrue(r.pLive() < 0.5, "delta over the threshold: low P(live)");
    }

    @Test
    void atExactlyTheThresholdTheVoteIsUndecided() {
        // Backdrop (0.40, 0.36); a highlight at (0.44, 0.36) - offset by ~PROVISIONAL_THRESHOLD along r.
        RgbImage atThreshold = solid(20, 20, 112, 92, 51);
        CueResult r = new WhitePointConsistencyCue().evaluate(BACKDROP, atThreshold);
        assertEquals(0.5, r.pLive(), 0.05);
    }

    @Test
    void noBackdropMeansAbstain() {
        CueResult r = new WhitePointConsistencyCue().evaluate(null, LIVE_HIGHLIGHT);
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, r.abstainReason());
    }

    @Test
    void aTooSmallFaceCropAbstains() {
        CueResult r = new WhitePointConsistencyCue().evaluate(BACKDROP, solid(4, 4, 105, 92, 58));
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL, r.abstainReason());
    }

    @Test
    void theHighlightIsTheBrightestPixelsNotTheWholeCrop() {
        // A crop that is mostly one colour (dark) with a small bright patch matching the live highlight: the
        // highlight fraction should recover the bright patch's chromaticity, not the dominant dark colour's.
        int w = 20;
        int h = 20;
        byte[] px = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            px[3 * i] = (byte) 10;
            px[3 * i + 1] = (byte) 10;
            px[3 * i + 2] = (byte) 10;
        }
        // Top-left 5x5 block (25 of 400 px = 6.25%, at least the 5% highlight fraction) is the bright live highlight.
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                int i = y * w + x;
                px[3 * i] = (byte) 105;
                px[3 * i + 1] = (byte) 92;
                px[3 * i + 2] = (byte) 58;
            }
        }
        RgbImage mostlyDark = RgbImage.of(w, h, px);
        double[] rg = WhitePointConsistencyCue.highlightChromaticity(mostlyDark);
        assertEquals(0.41, rg[0], 0.01);
        assertEquals(0.36, rg[1], 0.01);
    }
}
