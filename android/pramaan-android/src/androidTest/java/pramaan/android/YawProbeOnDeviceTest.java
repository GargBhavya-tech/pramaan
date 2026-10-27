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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.challenge.HeadPoseEstimator;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.FrameObservation;
import pramaan.core.engine.PipelineFrameAnalyser;
import pramaan.core.engine.StandardEngines;
import pramaan.core.landmarks.FaceLandmarks;
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
 * Which way does the engine think a real head turned? The person is told to look straight, then to turn to their OWN left, back to
 * straight, then to their OWN right, while the signed yaw the engine's turn detector uses is logged per phase. By the documented
 * convention a turn to the resident's own left must read positive and to the right negative; if it reads the other way on real
 * frames, a person who turns the right way is judged to have turned the wrong way. Only yaw numbers are logged, never an image.
 */
@RunWith(AndroidJUnit4.class)
public class YawProbeOnDeviceTest {

    private static final String TAG = "PramaanYaw";
    private static final String[] PHASES = {"STRAIGHT", "YOUR_LEFT", "STRAIGHT_AGAIN", "YOUR_RIGHT", "STRAIGHT_LAST"};
    private static final String[] SAY = {
        "Look STRAIGHT at the camera and hold still",
        "Now turn your head to YOUR OWN LEFT (your left shoulder) and hold it",
        "Back to STRAIGHT, hold still",
        "Now turn your head to YOUR OWN RIGHT (your right shoulder) and hold it",
        "Back to STRAIGHT, hold still"};
    private static final long[] SECONDS = {4, 6, 3, 6, 3};

    @Test
    public void theSignOfAYawTurnOnRealFrames() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();
        AssetManager assets = inst.getContext().getAssets();
        Assume.assumeTrue("no front camera", ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));
        String[] modelFiles = assets.list("pramaan/models");
        Assume.assumeTrue("models not in the test APK", modelFiles != null && modelFiles.length > 0);

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
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
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
            CameraFrameSource camera = new CameraFrameSource(ctx, true, 640, 480, 3.0, Clock.systemUTC());
            camera.setPreviewSurface(screen.scanView().awaitPreviewSurface(8000));
            try {
                camera.discover();
                camera.start(null, new FrameSink() {
                    @Override
                    public void onFrame(Frame frame) {
                        latest.set(frame);
                    }

                    @Override
                    public void onDeviceEvent(DeviceEvent event) {
                    }
                });
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400);
                screen.say("Hold the phone upright at your face and look straight at it. The poses start as soon as it sees you.");
                FaceLandmarks frontal = null;
                Frame last = null;
                long findBy = System.currentTimeMillis() + 40_000;
                int seen = 0;
                int withFace = 0;
                java.util.Map<String, Integer> why = new java.util.TreeMap<>();
                while (frontal == null && System.currentTimeMillis() < findBy) {
                    Frame f = latest.get();
                    if (f == null || f == last) {
                        Thread.sleep(15);
                        continue;
                    }
                    last = f;
                    seen++;
                    FrameObservation o = analyser.analyse(f);
                    if (o.landmarks() != null) {
                        withFace++;
                    }
                    why.merge(String.valueOf(o.guidance()), 1, Integer::sum);
                    if (o.landmarks() != null && o.qualityPasses()) {
                        frontal = o.landmarks();
                    }
                }
                assertTrue("a clear frontal face was seen within 40 s: frames analysed " + seen + ", with a face " + withFace
                        + ", guidance " + why, frontal != null);
                for (int p = 0; p < PHASES.length; p++) {
                    screen.say(SAY[p]);
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250);
                    long end = System.currentTimeMillis() + SECONDS[p] * 1000;
                    List<Double> yaws = new ArrayList<>();
                    // the first moments of a phase are the person moving: only the later frames are the held pose
                    long settleUntil = System.currentTimeMillis() + 1800;
                    while (System.currentTimeMillis() < end) {
                        Frame f = latest.get();
                        if (f == null || f == last) {
                            Thread.sleep(15);
                            continue;
                        }
                        last = f;
                        FrameObservation o = analyser.analyse(f);
                        FaceLandmarks lm = o.landmarks();
                        if (lm == null) {
                            continue;
                        }
                        if (System.currentTimeMillis() >= settleUntil) {
                            yaws.add(HeadPoseEstimator.relativeYawDegrees(frontal, lm));
                        }
                    }
                    report.putString("yaw_" + PHASES[p], summary(yaws));
                    Log.i(TAG, PHASES[p] + " = " + summary(yaws));
                }
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 600);
                screen.say("Done");
                assertTrue("a frontal pose was found", frontal != null);
            } finally {
                camera.stop();
                tone.release();
            }
        }
        report.putString("convention", "documented: a turn to the resident's OWN left reads POSITIVE yaw, own right NEGATIVE");
        inst.sendStatus(0, report);
    }

    private static String summary(List<Double> v) {
        if (v.isEmpty()) {
            return "no frames";
        }
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        double sum = 0;
        for (double d : s) {
            sum += d;
        }
        return String.format("n=%d median %.1f mean %.1f min %.1f max %.1f", s.size(), s.get(s.size() / 2), sum / s.size(), s.get(0),
                s.get(s.size() - 1));
    }
}
