/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.StandardEngines;
import pramaan.core.image.RgbImage;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.session.EngineBundle;

/**
 * Build Map #79 on a phone: the engine loads its verified bundle — the YAML policy, the operating-point table and
 * exactly the table's models — and scores a bundled face with the same models the desktop runs. Loading the
 * policy also proves Jackson YAML parses on Android without reaching SnakeYAML's java.beans introspection.
 *
 * <p>The models and the face image are copied into the test APK at build time from {@code models/} and the
 * MockMDS stream fixture, both gitignored; without them the test is skipped, not failed.
 */
@RunWith(AndroidJUnit4.class)
public class EngineOnDeviceTest {

    @Test
    public void theEngineLoadsItsBundleAndScoresABundledFace() throws Exception {
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        Assume.assumeTrue("models and the face fixture were not available at build time",
                exists(assets, "pramaan/models", StandardEngines.YUNET) && exists(assets, "pramaan", "face.jpg"));
        try (EngineBundle bundle = AndroidEngines.loadFromAssets(assets, "pramaan")) {
            RgbImage img = new BitmapImageDecoder().decode(AndroidEngines.read(assets, "pramaan/face.jpg"));
            YuNetDetector yunet = new YuNetDetector(bundle.models().get(StandardEngines.YUNET),
                    StandardEngines.DETECT_SCORE, StandardEngines.DETECT_NMS);
            List<FaceBox> faces = yunet.detect(img);
            assertEquals("one face in the bundled image", 1, faces.size());
            PassiveScorer passive = new PassiveScorer(bundle.models().get(StandardEngines.MINIFAS_V2),
                    bundle.models().get(StandardEngines.MINIFAS_V1SE));
            double p = passive.liveProbability(img, faces.get(0));
            assertTrue("a probability: " + p, p >= 0 && p <= 1);
        }
    }

    private static boolean exists(AssetManager assets, String dir, String name) throws IOException {
        String[] list = assets.list(dir);
        return list != null && Arrays.asList(list).contains(name);
    }
}
