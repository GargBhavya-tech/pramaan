/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import static org.junit.Assert.assertTrue;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Debug;
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
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.MoireCue;
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
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;

/**
 * The whole per-frame analyser on a phone: decode, face detection, quality gate, passive models, landmarks, the moire cue and
 * the embedding, exactly as {@code StandardEngines.create} wires them, over the 63-frame MockMDS clip (one real person). This is
 * the cost the engine must keep under the stream's frame interval; the benchmark test times only detection and passive scoring.
 *
 * <p>The frames are given 333 ms apart (the 3 fps an SBI stream guarantees) so the pipeline measures the rate it will meet.
 * It asserts only that the run is sane, never a budget: a slow phone is a finding. Results go to Logcat (tag
 * {@code PramaanFull}) and to the instrumentation status. Skipped when the frames are not in the test APK (they come from a
 * gitignored fixture).
 */
@RunWith(AndroidJUnit4.class)
public class FullPipelineOnDeviceTest {

    private static final String TAG = "PramaanFull";
    private static final int WARM_UP = 5;
    private static final int PASSES = 3;

    @Test
    public void thePerFrameAnalyserOnTheClipOnThisDevice() throws Exception {
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

            List<Double> ms = new ArrayList<>();
            int frames = 0;
            int withFace = 0;
            int qualityPass = 0;
            int scored = 0;
            double passiveSum = 0;
            int moireVotes = 0;
            int moireAbstain = 0;
            int embeddings = 0;
            double moirePeakSum = 0;
            for (int pass = 0; pass < PASSES; pass++) {
                for (int i = 0; i < jpegs.size(); i++) {
                    Frame frame = new Frame(jpegs.get(i), PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                            Instant.ofEpochMilli((pass * jpegs.size() + i) * 333L), 0, null);
                    long t0 = System.nanoTime();
                    FrameObservation o = analyser.analyse(frame);
                    double elapsed = (System.nanoTime() - t0) / 1e6;
                    if (pass * jpegs.size() + i < WARM_UP) {
                        continue;
                    }
                    ms.add(elapsed);
                    frames++;
                    if (o.decoded() && o.landmarks() != null) {
                        withFace++;
                    }
                    if (o.qualityPasses()) {
                        qualityPass++;
                    }
                    if (!Double.isNaN(o.passive())) {
                        scored++;
                        passiveSum += o.passive();
                    }
                    if (o.moire() != null) {
                        if (o.moire().abstained()) {
                            moireAbstain++;
                        } else {
                            moireVotes++;
                            moirePeakSum += o.moire().rawValue();
                        }
                    }
                    if (o.embedding() != null) {
                        embeddings++;
                    }
                }
            }
            Collections.sort(ms);
            double median = ms.get(ms.size() / 2);
            double p95 = ms.get((int) Math.min(ms.size() - 1, Math.round(ms.size() * 0.95)));
            Debug.MemoryInfo mem = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mem);
            report.putString("frameSource", jpegs.size() + " frames of the MockMDS clip x " + PASSES + " passes, first " + WARM_UP
                    + " frames discarded");
            report.putString("measuredFrames", Integer.toString(frames));
            report.putString("analyseMedianMs", String.format("%.0f", median));
            report.putString("analyseP95Ms", String.format("%.0f", p95));
            report.putString("analyseMaxMs", String.format("%.0f", ms.get(ms.size() - 1)));
            report.putString("framesPerSecondAtMedian", String.format("%.1f", 1000.0 / median));
            report.putString("withFaceAndLandmarks", withFace + "/" + frames);
            report.putString("qualityPassed", qualityPass + "/" + frames);
            report.putString("passiveScored", scored + " frames, mean P(live) " + (scored == 0 ? "n/a"
                    : String.format("%.3f", passiveSum / scored)));
            report.putString("moire", moireVotes + " votes (mean peakiness "
                    + (moireVotes == 0 ? "n/a" : String.format("%.1f", moirePeakSum / moireVotes)) + "), " + moireAbstain
                    + " abstained");
            report.putString("embeddingsComputed", Integer.toString(embeddings));
            report.putString("nativeHeapAllocatedMB", Long.toString(Debug.getNativeHeapAllocatedSize() / (1024 * 1024)));
            report.putString("totalPssMB", Integer.toString(mem.getTotalPss() / 1024));
            // The clip's faces are small, so the quality gate rejects every frame and the heavy stages never run above. Force every
            // stage on each detected face and time them one by one: the cost a frame would have if it passed the gate.
            MoireCue moire = new MoireCue();
            SFaceEmbedder sface = new SFaceEmbedder(bundle.models().get(StandardEngines.SFACE));
            PassiveScorer passive = new PassiveScorer(bundle.models().get(StandardEngines.MINIFAS_V2),
                    bundle.models().get(StandardEngines.MINIFAS_V1SE));
            BitmapFrameDecoder decoder = new BitmapFrameDecoder();
            List<Double> dec = new ArrayList<>();
            List<Double> det = new ArrayList<>();
            List<Double> pas = new ArrayList<>();
            List<Double> lan = new ArrayList<>();
            List<Double> moi = new ArrayList<>();
            List<Double> emb = new ArrayList<>();
            List<Double> total = new ArrayList<>();
            int moireAbstained = 0;
            int forcedFrames = 0;
            for (int pass = 0; pass < PASSES; pass++) {
                for (int i = 0; i < jpegs.size(); i++) {
                    Frame frame = new Frame(jpegs.get(i), PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, Instant.EPOCH, 0, null);
                    long a = System.nanoTime();
                    RgbImage img = decoder.decode(frame);
                    long b = System.nanoTime();
                    List<FaceBox> faces = detector.detect(img);
                    long c = System.nanoTime();
                    if (faces.size() != 1 || pass * jpegs.size() + i < WARM_UP) {
                        continue;
                    }
                    FaceBox f = faces.get(0);
                    passive.liveProbability(img, f);
                    long d = System.nanoTime();
                    landmarks.locate(img, f);
                    long e = System.nanoTime();
                    int x0 = Math.round(f.x());
                    int y0 = Math.round(f.y());
                    RgbImage crop = ImageOps.crop(img, x0, y0, x0 + Math.round(f.width()) - 1, y0 + Math.round(f.height()) - 1);
                    if (moire.evaluate(crop).abstained()) {
                        moireAbstained++;
                    }
                    long g = System.nanoTime();
                    sface.embed(img, f.fivePoints());
                    long h = System.nanoTime();
                    dec.add((b - a) / 1e6);
                    det.add((c - b) / 1e6);
                    pas.add((d - c) / 1e6);
                    lan.add((e - d) / 1e6);
                    moi.add((g - e) / 1e6);
                    emb.add((h - g) / 1e6);
                    total.add((h - a) / 1e6);
                    forcedFrames++;
                }
            }
            report.putString("forcedFrames", Integer.toString(forcedFrames));
            report.putString("forcedDecodeMedianMs", median(dec));
            report.putString("forcedDetectMedianMs", median(det));
            report.putString("forcedPassiveMedianMs", median(pas));
            report.putString("forcedLandmarksMedianMs", median(lan));
            report.putString("forcedMoireMedianMs", median(moi));
            report.putString("forcedMoireAbstained", moireAbstained + "/" + forcedFrames + " (face smaller than 128 px)");
            report.putString("forcedEmbedMedianMs", median(emb));
            report.putString("forcedTotalMedianMs", median(total));
            report.putString("forcedTotalP95Ms", p95(total));
            report.putString("totalPssAfterForcedMB", Integer.toString(forcedPss()));
            assertTrue("some frames were analysed", frames > 0);
            assertTrue("a face was found in most frames of a clip of one person", withFace > frames / 2);
        }
        for (String key : report.keySet()) {
            Log.i(TAG, key + " = " + report.get(key));
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
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

    private static int forcedPss() {
        Debug.MemoryInfo m = new Debug.MemoryInfo();
        Debug.getMemoryInfo(m);
        return m.getTotalPss() / 1024;
    }

    private static boolean exists(AssetManager assets, String dir, String name) throws IOException {
        String[] list = assets.list(dir);
        return list != null && Arrays.asList(list).contains(name);
    }
}
