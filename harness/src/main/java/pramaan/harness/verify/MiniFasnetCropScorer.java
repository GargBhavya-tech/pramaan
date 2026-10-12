/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import ai.onnxruntime.OrtException;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * Re-scores a sample crop with MiniFASNet V2 (scale 2.7): the crop already is that model's context, so it is
 * only resized to 80x80. The client averages V2 and V1SE (scale 4.0); V1SE's wider context is not in the
 * crop, so the server's score is V2 alone — a second opinion, not a replay of the client's arithmetic.
 */
public final class MiniFasnetCropScorer implements CropScorer, AutoCloseable {

    private static final int SIZE = 80;
    private final OnnxModel v2;

    public MiniFasnetCropScorer(OnnxModel v2) {
        this.v2 = v2;
    }

    @Override
    public double pLive(RgbImage crop) throws OrtException {
        float[] logits = v2.run(ImageOps.toChwBgr(ImageOps.resize(crop, SIZE, SIZE)), new long[] {1, 3, SIZE, SIZE}).values();
        double max = Math.max(Math.max(logits[0], logits[1]), logits[2]);
        double e0 = Math.exp(logits[0] - max);
        double e1 = Math.exp(logits[1] - max);
        double e2 = Math.exp(logits[2] - max);
        return e1 / (e0 + e1 + e2);
    }

    @Override
    public void close() throws OrtException {
        v2.close();
    }
}
