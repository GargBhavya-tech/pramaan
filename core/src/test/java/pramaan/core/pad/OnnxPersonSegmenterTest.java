/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;

/**
 * Route B's preparation and reading of a network, checked with a fake network so no model file is needed: the input is
 * laid out and normalised the way the {@code Spec} says, and the output is read from wherever the {@code Spec} says.
 */
class OnnxPersonSegmenterTest {

    private static final int[] FACE = {260, 60, 380, 200};

    private static RgbImage frame() {
        byte[] px = new byte[640 * 480 * 3];
        java.util.Arrays.fill(px, (byte) 200);
        return RgbImage.of(640, 480, px);
    }

    /** A network that says "person" on the right half of its output grid. */
    private static OnnxPersonSegmenter.Result rightHalf(int h, int w, boolean channelsFirst, int channels, int personChannel,
            float on, float off) {
        float[] out = new float[h * w * channels];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < channels; c++) {
                    float v = c == personChannel ? (x >= w / 2 ? on : off) : (x >= w / 2 ? off : on);
                    out[channelsFirst ? c * h * w + y * w + x : (y * w + x) * channels + c] = v;
                }
            }
        }
        return new OnnxPersonSegmenter.Result(out, channelsFirst ? new long[] {1, channels, h, w}
                : new long[] {1, h, w, channels});
    }

    @Test
    void aSingleChannelNhwcProbabilityMapIsReadAndLaidBackOntoTheFrame() {
        OnnxPersonSegmenter seg = new OnnxPersonSegmenter((in, shape) -> rightHalf(256, 256, false, 1, 0, 0.95f, 0.05f),
                OnnxPersonSegmenter.Spec.selfie256());
        PersonMask m = seg.segment(frame(), FACE, null);
        assertTrue(m.person(140, 60), "right half is a person");
        assertFalse(m.person(20, 60), "left half is room");
    }

    @Test
    void aTwoChannelNchwLogitMapUsesTheNamedChannelAndASigmoid() {
        OnnxPersonSegmenter.Spec spec = new OnnxPersonSegmenter.Spec(128, 128, true, new float[3], new float[] {1, 1, 1}, 1,
                true, false, 0.5f);
        OnnxPersonSegmenter seg = new OnnxPersonSegmenter((in, shape) -> rightHalf(128, 128, true, 2, 1, 4f, -4f), spec);
        PersonMask m = seg.segment(frame(), FACE, null);
        assertTrue(m.person(140, 60));
        assertFalse(m.person(20, 60));
    }

    @Test
    void aSaliencyMapIsStretchedToZeroOneBeforeTheThreshold() {
        // Values 0.2..0.4: under a plain 0.5 threshold nothing would be a person; stretched, the high half is.
        OnnxPersonSegmenter seg = new OnnxPersonSegmenter((in, shape) -> rightHalf(320, 320, true, 1, 0, 0.4f, 0.2f),
                OnnxPersonSegmenter.Spec.u2net320());
        PersonMask m = seg.segment(frame(), FACE, null);
        assertTrue(m.person(140, 100));
        assertFalse(m.person(20, 100));
    }

    @Test
    void theInputIsLaidOutAndNormalisedAsTheSpecSays() {
        AtomicReference<long[]> shapeSeen = new AtomicReference<>();
        AtomicReference<float[]> inputSeen = new AtomicReference<>();
        OnnxPersonSegmenter seg = new OnnxPersonSegmenter((in, shape) -> {
            shapeSeen.set(shape);
            inputSeen.set(in);
            return rightHalf(320, 320, true, 1, 0, 0.9f, 0.1f);
        }, OnnxPersonSegmenter.Spec.u2net320());
        seg.segment(frame(), FACE, null);
        assertEquals(java.util.List.of(1L, 3L, 320L, 320L), java.util.Arrays.stream(shapeSeen.get()).boxed()
                .collect(java.util.stream.Collectors.toList()));
        // pixel 200 -> 200/255 = 0.784; red plane: (0.784 - 0.485) / 0.229
        assertEquals((200 / 255f - 0.485f) / 0.229f, inputSeen.get()[0], 1e-3);
        // the first green value is at the start of the second plane
        assertEquals((200 / 255f - 0.456f) / 0.224f, inputSeen.get()[320 * 320], 1e-3);
    }

    @Test
    void theFaceIsAlwaysAPersonEvenWhereTheNetworkSaysNobody() {
        OnnxPersonSegmenter seg = new OnnxPersonSegmenter((in, shape) -> rightHalf(256, 256, false, 1, 0, 0.0f, 0.0f),
                OnnxPersonSegmenter.Spec.selfie256());
        PersonMask m = seg.segment(frame(), FACE, null);
        assertTrue(m.person(80, 40), "the face box, grown, is masked");
    }

    @Test
    void aNetworkThatFailsFallsBackToTheFaceAndTheColumnUnderIt() {
        OnnxPersonSegmenter seg = new OnnxPersonSegmenter((in, shape) -> {
            throw new IllegalStateException("model failed");
        }, OnnxPersonSegmenter.Spec.selfie256());
        PersonMask m = seg.segment(frame(), FACE, null);
        assertTrue(m.person(80, 40), "the face");
        assertTrue(m.person(80, 110), "the column under it");
        assertFalse(m.person(10, 110), "and not the far side of the room");
    }
}
