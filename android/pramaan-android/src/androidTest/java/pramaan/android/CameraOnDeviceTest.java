/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.FrameObservation;
import pramaan.core.engine.PipelineFrameAnalyser;
import pramaan.core.engine.StandardEngines;
import pramaan.core.landmarks.LandmarkProvider;
import pramaan.core.landmarks.OnnxBlendshapeLandmarker;
import pramaan.core.landmarks.OnnxFaceMeshLandmarker;
import pramaan.core.pad.ColorTextureCue;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.pipeline.FramePipeline;
import pramaan.core.session.EngineBundle;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;

/**
 * The phone's front camera through {@link CameraFrameSource} into the whole per-frame analyser, with a live face: the first run
 * on this project of the Camera APIs and of the quality gate, passive, moire and embedding stages on frames that really pass.
 * Whoever holds the phone is recorded in memory only; nothing is written and only numbers are logged.
 *
 * <p>Timeline: a beep, 8 seconds to hold the phone upright at face height with the screen towards you, a beep, 15 seconds
 * measured, a final tone. Results go to Logcat (tag {@code PramaanCamera}) and to the instrumentation status. Skipped on a
 * device without a front camera or without the models.
 */
@RunWith(AndroidJUnit4.class)
public class CameraOnDeviceTest {

    private static final String TAG = "PramaanCamera";
    private static final long POSITION_MS = 8_000;
    private static final long MEASURE_MS = 15_000;

    @Test
    public void theFrontCameraFeedsTheWholeAnalyser() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();
        AssetManager assets = inst.getContext().getAssets();
        Assume.assumeTrue("no front camera", ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));
        String[] modelFiles = assets.list("pramaan/models");
        Assume.assumeTrue("models not in the test APK", modelFiles != null && modelFiles.length > 0);
        // The shell starts this screen (adb shell am start): a test process may not start an activity from the background on
        // some phones (MIUI aborts it), a shell start is allowed.
        long screenDeadline = System.currentTimeMillis() + 120_000;
        CameraTestActivity screen;
        while ((screen = CameraTestActivity.current) == null) {
            Assume.assumeTrue("the test screen was not started within 120 s", System.currentTimeMillis() < screenDeadline);
            Thread.sleep(250);
        }
        long permissionDeadline = System.currentTimeMillis() + 60_000;
        while (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Assume.assumeTrue("the camera permission was not granted within 60 s", System.currentTimeMillis() < permissionDeadline);
            Thread.sleep(250);
        }

        Bundle report = new Bundle();
        report.putString("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android "
                + android.os.Build.VERSION.RELEASE);
        try (EngineBundle bundle = AndroidEngines.loadFromAssets(assets, "pramaan")) {
            YuNetDetector detector = new YuNetDetector(bundle.models().get(StandardEngines.YUNET), StandardEngines.DETECT_SCORE,
                    StandardEngines.DETECT_NMS);
            FramePipeline pipeline = new FramePipeline(new BitmapFrameDecoder(), detector,
                    new PassiveScorer(bundle.models().get(StandardEngines.MINIFAS_V2),
                            bundle.models().get(StandardEngines.MINIFAS_V1SE)), 3.0, System::nanoTime);
            OnnxFaceMeshLandmarker mesh = new OnnxFaceMeshLandmarker(bundle.models().get(StandardEngines.LANDMARKS));
            LandmarkProvider landmarks = bundle.models().containsKey(StandardEngines.BLENDSHAPES)
                    ? new OnnxBlendshapeLandmarker(mesh, bundle.models().get(StandardEngines.BLENDSHAPES)) : mesh;
            PipelineFrameAnalyser analyser = new PipelineFrameAnalyser(pipeline, landmarks,
                    new SFaceEmbedder(bundle.models().get(StandardEngines.SFACE)), new ColorTextureCue(null, 0));

            AtomicReference<Frame> latest = new AtomicReference<>();
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            long[] delivered = {0};
            int[] frameSize = {0, 0};
            CameraFrameSource camera = new CameraFrameSource(ctx, true, 640, 480, 3.0, Clock.systemUTC());
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            try {
                camera.discover();
                camera.start(null, new FrameSink() {
                    @Override
                    public void onFrame(Frame frame) {
                        delivered[0]++;
                        frameSize[0] = frame.width();
                        frameSize[1] = frame.height();
                        latest.set(frame);
                    }

                    @Override
                    public void onDeviceEvent(DeviceEvent event) {
                        events.add(event.type() + ": " + event.detail());
                    }
                });
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400);
                for (long left = POSITION_MS / 1000; left > 0; left--) {
                    screen.say("Hold the phone upright at face height, screen towards you."
                            + "\n\nFace filling the screen, good light.\n\nMeasuring starts in " + left + " s");
                    Thread.sleep(1000);
                }
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400);

                long deliveredAtStart = delivered[0];
                long end = System.currentTimeMillis() + MEASURE_MS;
                List<Double> ms = new ArrayList<>();
                List<Double> passive = new ArrayList<>();
                List<Double> peaks = new ArrayList<>();
                Map<String, Integer> guidance = new TreeMap<>();
                int processed = 0;
                int withFace = 0;
                int qualityPass = 0;
                int moireAbstain = 0;
                int embeddings = 0;
                Frame last = null;
                while (System.currentTimeMillis() < end) {
                    Frame f = latest.get();
                    if (f == null || f == last) {
                        Thread.sleep(15);
                        continue;
                    }
                    last = f;
                    screen.say("Hold still and look at the camera.\n\n" + Math.max(0, (end - System.currentTimeMillis()) / 1000) + " s left");
                    long t0 = System.nanoTime();
                    FrameObservation o = analyser.analyse(f);
                    ms.add((System.nanoTime() - t0) / 1e6);
                    processed++;
                    if (o.landmarks() != null) {
                        withFace++;
                    }
                    if (o.qualityPasses()) {
                        qualityPass++;
                    }
                    if (o.guidance() != null) {
                        guidance.merge(o.guidance().toString(), 1, Integer::sum);
                    }
                    if (!Double.isNaN(o.passive())) {
                        passive.add(o.passive());
                    }
                    if (o.moire() != null) {
                        if (o.moire().abstained()) {
                            moireAbstain++;
                        } else {
                            peaks.add(o.moire().rawValue());
                        }
                    }
                    if (o.embedding() != null) {
                        embeddings++;
                    }
                }
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 600);
                screen.say("Done. You can put the phone down.");
                long deliveredInWindow = delivered[0] - deliveredAtStart;
                report.putString("frameSize", frameSize[0] + "x" + frameSize[1] + " (upright)");
                report.putString("deliveredInWindow", deliveredInWindow + " frames in " + MEASURE_MS / 1000 + " s = "
                        + String.format("%.1f", deliveredInWindow * 1000.0 / MEASURE_MS) + " fps");
                report.putString("analysed", processed + " (the rest were dropped: the analyser is slower than the stream)");
                report.putString("analyseMedianMs", median(ms));
                report.putString("analyseP95Ms", p95(ms));
                report.putString("withFaceAndLandmarks", withFace + "/" + processed);
                report.putString("qualityPassed", qualityPass + "/" + processed);
                report.putString("guidance", guidance.toString());
                report.putString("passive", passive.size() + " frames, P(live) " + stats(passive));
                report.putString("moire", peaks.size() + " votes, peakiness " + stats(peaks) + "; " + moireAbstain + " abstained");
                report.putString("embeddings", Integer.toString(embeddings));
                report.putString("events", events.toString());
                assertTrue("the camera delivered frames: " + events, deliveredInWindow > 3);
            } finally {
                camera.stop();
                tone.release();
            }
        }
        for (String key : report.keySet()) {
            Log.i(TAG, key + " = " + report.get(key));
        }
        inst.sendStatus(0, report);
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

    private static String stats(List<Double> v) {
        if (v.isEmpty()) {
            return "n/a";
        }
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        double sum = 0;
        for (double d : s) {
            sum += d;
        }
        return String.format("min %.3f / median %.3f / mean %.3f / max %.3f", s.get(0), s.get(s.size() / 2), sum / s.size(),
                s.get(s.size() - 1));
    }
}
