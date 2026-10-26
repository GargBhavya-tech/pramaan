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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.StandardEngines;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.InferenceOptions;
import pramaan.core.landmarks.LandmarkProvider;
import pramaan.core.landmarks.OnnxBlendshapeLandmarker;
import pramaan.core.landmarks.OnnxFaceMeshLandmarker;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.session.EngineBundle;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;

/**
 * The whole per-frame chain on a phone under each inference profile the runtime offers: CPU with 2 and 4 threads, XNNPACK and
 * NNAPI. {@code AcceleratorParity} already showed the accelerated scores match the CPU's; this times them, because parity
 * without speed is not a reason to switch one on. Each stage is forced on every detected face of the MockMDS clip (its faces are
 * small, so the quality gate would otherwise skip the heavy stages). Results go to Logcat (tag {@code PramaanChain}) and to the
 * instrumentation status. Skipped when the clip frames are not in the test APK.
 */
@RunWith(AndroidJUnit4.class)
public class ChainByProfileOnDeviceTest {

    private static final String TAG = "PramaanChain";
    private static final int WARM_UP = 6;
    private static final int PASSES = 3;

    @Test
    public void theChainUnderEachInferenceProfile() throws Exception {
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        String[] names = assets.list("pramaan/frames");
        Assume.assumeTrue("the clip frames were not available at build time", names != null && names.length > 0
                && exists(assets, "pramaan/models", StandardEngines.YUNET));
        Arrays.sort(names);
        List<byte[]> jpegs = new ArrayList<>();
        for (String n : names) {
            jpegs.add(AndroidEngines.read(assets, "pramaan/frames/" + n));
        }
        Bundle report = new Bundle();
        report.putString("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android "
                + android.os.Build.VERSION.RELEASE + ", " + Runtime.getRuntime().availableProcessors() + " cores");
        InferenceOptions[] profiles = {
            InferenceOptions.of(InferenceOptions.Accelerator.CPU, 2, 1, true),
            InferenceOptions.of(InferenceOptions.Accelerator.CPU, 4, 1, true),
            InferenceOptions.of(InferenceOptions.Accelerator.XNNPACK, 2, 1, true),
            InferenceOptions.of(InferenceOptions.Accelerator.XNNPACK, 4, 1, true),
            InferenceOptions.of(InferenceOptions.Accelerator.NNAPI, 2, 1, true)};
        String[] labels = {"cpu2", "cpu4", "xnnpack2", "xnnpack4", "nnapi"};
        int fastest = Integer.MAX_VALUE;
        for (int p = 0; p < profiles.length; p++) {
            String key = labels[p];
            try (EngineBundle bundle = AndroidEngines.loadFromAssets(assets, "pramaan", profiles[p])) {
                String provider = bundle.models().get(StandardEngines.YUNET).provider()
                        + (bundle.models().get(StandardEngines.YUNET).fallbackReason() == null ? ""
                        : " (fallback: " + bundle.models().get(StandardEngines.YUNET).fallbackReason() + ")");
                YuNetDetector detector = new YuNetDetector(bundle.models().get(StandardEngines.YUNET),
                        StandardEngines.DETECT_SCORE, StandardEngines.DETECT_NMS);
                PassiveScorer passive = new PassiveScorer(bundle.models().get(StandardEngines.MINIFAS_V2),
                        bundle.models().get(StandardEngines.MINIFAS_V1SE));
                OnnxFaceMeshLandmarker mesh = new OnnxFaceMeshLandmarker(bundle.models().get(StandardEngines.LANDMARKS));
                LandmarkProvider landmarks = bundle.models().containsKey(StandardEngines.BLENDSHAPES)
                        ? new OnnxBlendshapeLandmarker(mesh, bundle.models().get(StandardEngines.BLENDSHAPES)) : mesh;
                SFaceEmbedder sface = new SFaceEmbedder(bundle.models().get(StandardEngines.SFACE));
                BitmapFrameDecoder decoder = new BitmapFrameDecoder();
                List<Double> det = new ArrayList<>();
                List<Double> pas = new ArrayList<>();
                List<Double> lan = new ArrayList<>();
                List<Double> emb = new ArrayList<>();
                List<Double> total = new ArrayList<>();
                double passiveSum = 0;
                int measured = 0;
                int seen = 0;
                for (int pass = 0; pass < PASSES; pass++) {
                    for (byte[] jpeg : jpegs) {
                        Frame frame = new Frame(jpeg, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, Instant.EPOCH, 0, null);
                        long a = System.nanoTime();
                        RgbImage img = decoder.decode(frame);
                        long b = System.nanoTime();
                        List<FaceBox> faces = detector.detect(img);
                        long c = System.nanoTime();
                        if (faces.size() != 1 || seen++ < WARM_UP) {
                            continue;
                        }
                        FaceBox f = faces.get(0);
                        double pl = passive.liveProbability(img, f);
                        long d = System.nanoTime();
                        landmarks.locate(img, f);
                        long e = System.nanoTime();
                        sface.embed(img, f.fivePoints());
                        long g = System.nanoTime();
                        det.add((c - b) / 1e6);
                        pas.add((d - c) / 1e6);
                        lan.add((e - d) / 1e6);
                        emb.add((g - e) / 1e6);
                        total.add((g - a) / 1e6);
                        passiveSum += pl;
                        measured++;
                    }
                }
                report.putString(key, "provider " + provider + "; frames " + measured + "; detect " + median(det) + " ms, passive "
                        + median(pas) + " ms, landmarks " + median(lan) + " ms, embed " + median(emb) + " ms; TOTAL median "
                        + median(total) + " ms (p95 " + p95(total) + "); mean passive P(live) "
                        + (measured == 0 ? "n/a" : String.format("%.4f", passiveSum / measured)));
                if (measured > 0) {
                    fastest = Math.min(fastest, Integer.parseInt(median(total)));
                }
            } catch (Exception | LinkageError e) {
                report.putString(key, "could not run: " + e);
            }
        }
        for (String key : report.keySet()) {
            Log.i(TAG, key + " = " + report.get(key));
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
        assertTrue("at least one profile ran the chain", fastest != Integer.MAX_VALUE);
    }

    private static String median(List<Double> v) {
        if (v.isEmpty()) {
            return "n/a";
        }
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return String.format("%.0f", s.get(s.size() / 2));
    }

    private static String p95(List<Double> v) {
        if (v.isEmpty()) {
            return "n/a";
        }
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return String.format("%.0f", s.get((int) Math.min(s.size() - 1, Math.round(s.size() * 0.95))));
    }

    private static boolean exists(AssetManager assets, String dir, String name) throws IOException {
        String[] list = assets.list(dir);
        return list != null && Arrays.asList(list).contains(name);
    }
}
