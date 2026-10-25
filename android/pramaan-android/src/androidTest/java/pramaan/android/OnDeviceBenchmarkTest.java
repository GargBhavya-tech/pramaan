/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import static org.junit.Assert.assertTrue;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.StandardEngines;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.AcceleratorParity;
import pramaan.core.inference.InferenceOptions;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.session.EngineBundle;

/**
 * What PS04 calls performance testing, on the phone: the cost of one frame through detection and the passive model on
 * this device's CPU path (the path the operating points were calibrated on), and, for each accelerator the runtime
 * offers, whether it really ran and how far its output is from the CPU's ({@link AcceleratorParity}).
 *
 * <p>It asserts only that the numbers are sane, never a budget: a phone that is slow is a finding, not a failure. The
 * results go to Logcat (tag {@code PramaanBench}) and to the instrumentation status, so they appear in the Gradle output
 * of {@code connectedDebugAndroidTest} and in the test report. Skipped, not failed, when the models are not in the test
 * APK. Nothing in this class has been run on a device yet.
 */
@RunWith(AndroidJUnit4.class)
public class OnDeviceBenchmarkTest {

    private static final String TAG = "PramaanBench";
    private static final int WARM_UP = 3;
    private static final int MEASURED = 30;

    @Test
    public void perFrameLatencyOnThisDevice() throws Exception {
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        Assume.assumeTrue("models and the face fixture were not available at build time",
                exists(assets, "pramaan/models", StandardEngines.YUNET) && exists(assets, "pramaan", "face.jpg"));
        Bundle report = new Bundle();
        report.putString("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android "
                + android.os.Build.VERSION.RELEASE + ", " + Runtime.getRuntime().availableProcessors() + " cores");
        try (EngineBundle bundle = AndroidEngines.loadFromAssets(assets, "pramaan")) {
            RgbImage img = new BitmapImageDecoder().decode(AndroidEngines.read(assets, "pramaan/face.jpg"));
            YuNetDetector yunet = new YuNetDetector(bundle.models().get(StandardEngines.YUNET), StandardEngines.DETECT_SCORE,
                    StandardEngines.DETECT_NMS);
            PassiveScorer passive = new PassiveScorer(bundle.models().get(StandardEngines.MINIFAS_V2),
                    bundle.models().get(StandardEngines.MINIFAS_V1SE));
            FaceBox face = yunet.detect(img).get(0);

            List<Double> detectMs = new ArrayList<>();
            List<Double> passiveMs = new ArrayList<>();
            for (int i = 0; i < WARM_UP + MEASURED; i++) {
                long t0 = System.nanoTime();
                yunet.detect(img);
                long t1 = System.nanoTime();
                passive.liveProbability(img, face);
                long t2 = System.nanoTime();
                if (i >= WARM_UP) {
                    detectMs.add((t1 - t0) / 1e6);
                    passiveMs.add((t2 - t1) / 1e6);
                }
            }
            put(report, "detect", detectMs);
            put(report, "passive", passiveMs);
            report.putString("heapUsedMB", String.valueOf((Runtime.getRuntime().totalMemory()
                    - Runtime.getRuntime().freeMemory()) / 1048576));
            assertTrue("sane timings", median(detectMs) > 0 && median(passiveMs) > 0);
        }
        for (String key : report.keySet()) {
            Log.i(TAG, key + " = " + report.get(key));
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }

    @Test
    public void whichAcceleratorsReallyRunAndHowFarTheyDriftFromTheCpu() throws Exception {
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        Assume.assumeTrue("the passive model was not available at build time",
                exists(assets, "pramaan/models", StandardEngines.MINIFAS_V2));
        byte[] model = AndroidEngines.read(assets, "pramaan/models/" + StandardEngines.MINIFAS_V2);
        float[] input = new float[1 * 3 * 80 * 80];
        Random random = new Random(7);
        for (int i = 0; i < input.length; i++) {
            input[i] = random.nextFloat() * 255f;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        Bundle report = new Bundle();
        for (InferenceOptions.Accelerator a : new InferenceOptions.Accelerator[] {InferenceOptions.Accelerator.XNNPACK,
                InferenceOptions.Accelerator.NNAPI}) {
            AcceleratorParity.Result r = AcceleratorParity.compare(model, InferenceOptions.lowResource(cores, a), input,
                    new long[] {1, 3, 80, 80});
            report.putString(a.name(), r.toString());
            Log.i(TAG, a + ": " + r);
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
        assertTrue("both requests returned a result, on the accelerator or on the CPU after a fallback",
                report.size() == 2);
    }

    private static void put(Bundle report, String name, List<Double> ms) {
        report.putString(name + "_p50_ms", String.format("%.1f", median(ms)));
        report.putString(name + "_p95_ms", String.format("%.1f", percentile(ms, 95)));
    }

    private static double median(List<Double> v) {
        return percentile(v, 50);
    }

    private static double percentile(List<Double> v, int p) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int i = (int) Math.min(s.size() - 1, Math.ceil(p / 100.0 * s.size()) - 1);
        return s.get(Math.max(0, i));
    }

    private static boolean exists(AssetManager assets, String dir, String name) throws IOException {
        String[] list = assets.list(dir);
        return list != null && Arrays.asList(list).contains(name);
    }
}
