/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** YuNet prior decoding and NMS, checked against hand-computed numbers (Build Map #19). */
class DetectionLogicTest {

    @Test
    void decodesOnePriorLikeOpenCv() {
        // Stride 32, 2 columns: prior index 3 is row 1, col 1.
        float[] cls = new float[4];
        float[] obj = new float[4];
        cls[3] = 0.81f;
        obj[3] = 1.0f;
        float[] bbox = new float[16];
        bbox[12] = 0.5f;               // cx offset -> (1 + 0.5) * 32 = 48
        bbox[13] = 0.25f;              // cy offset -> (1 + 0.25) * 32 = 40
        bbox[14] = (float) Math.log(2); // w = 2 * 32 = 64
        bbox[15] = 0f;                 // h = 1 * 32 = 32
        float[] kps = new float[40];
        kps[30] = 0.1f;                // right eye x -> (0.1 + 1) * 32 = 35.2
        List<FaceBox> out = YuNetDetector.decode(32, 2, cls, obj, bbox, kps, 0.5f);
        assertEquals(1, out.size());
        FaceBox b = out.get(0);
        assertEquals(0.9f, b.score(), 1e-4);
        assertEquals(48 - 32, b.x(), 1e-3);
        assertEquals(40 - 16, b.y(), 1e-3);
        assertEquals(64, b.width(), 1e-3);
        assertEquals(35.2f, b.landmark(0, 0), 1e-3);
    }

    @Test
    void nmsKeepsTheBestOfOverlappingBoxes() {
        float[] l = new float[10];
        FaceBox a = new FaceBox(0, 0, 100, 100, 0.9f, l);
        FaceBox b = new FaceBox(5, 5, 100, 100, 0.8f, l);
        FaceBox c = new FaceBox(300, 300, 50, 50, 0.7f, l);
        List<FaceBox> kept = YuNetDetector.nms(List.of(b, c, a), 0.3f);
        assertEquals(List.of(a, c), kept);
    }
}
