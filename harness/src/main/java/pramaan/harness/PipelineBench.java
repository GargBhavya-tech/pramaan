/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.pipeline.FramePipeline;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;
import pramaan.desktop.ImageIoFrameDecoder;
import pramaan.harness.corpus.ClipLoader;

/**
 * Build Map #28 "done when": per-stage latency measured on this machine, not budgeted.
 * {@code ./gradlew :harness:pipelineBench} (defaults to the MockMDS stream fixture at its measured ~21 fps).
 */
public final class PipelineBench {

    private PipelineBench() {
    }

    public static void main(String[] args) throws Exception {
        Path clip = Path.of(args.length > 0 ? args[0] : "sbi/testdata/stream-sample.mjpeg");
        double fps = args.length > 1 ? Double.parseDouble(args[1]) : 21.0;
        List<byte[]> frames = ClipLoader.load(clip);
        try (YuNetDetector detector = Models.detector(Path.of("models"));
                PassiveScorer passive = Models.passive(Path.of("models"))) {
            FramePipeline pipeline = new FramePipeline(new ImageIoFrameDecoder(), detector, passive, fps, System::nanoTime);
            Instant t = Instant.parse("2026-09-23T10:00:00Z");
            double passiveSum = 0;
            int passiveN = 0;
            int oneFace = 0;
            java.util.Map<pramaan.core.quality.QualityComponent, double[]> quality = new java.util.EnumMap<>(
                    pramaan.core.quality.QualityComponent.class);
            int qualityPassed = 0;
            for (int i = 0; i < frames.size(); i++) {
                Frame f = new Frame(frames.get(i), PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                        t.plusMillis(Math.round(i * 1000 / fps)), i, null);
                FramePipeline.Result r = pipeline.process(f);
                oneFace += r.gate == pramaan.core.gate.FaceGate.Status.ONE_FACE ? 1 : 0;
                if (r.quality != null) {
                    qualityPassed += r.quality.passes() ? 1 : 0;
                    r.quality.scores().forEach((c, v) -> {
                        double[] acc = quality.computeIfAbsent(c, k -> new double[2]);
                        acc[0] += v;
                        acc[1] += v < pramaan.core.quality.QualityReport.PASS ? 1 : 0;
                    });
                }
                if (!Double.isNaN(r.passive)) {
                    passiveSum += r.passive;
                    passiveN++;
                }
            }
            System.out.println("clip           : " + clip + " (" + frames.size() + " frames at " + fps + " fps)");
            System.out.println("java / os      : " + System.getProperty("java.version") + " / " + System.getProperty("os.name"));
            System.out.println("one face in    : " + oneFace + " of " + frames.size() + " frames");
            System.out.println("quality passed : " + qualityPassed + " of " + oneFace + " single-face frames");
            final int assessed = oneFace;
            quality.forEach((c, acc) -> System.out.printf("  %-19s mean score %.2f, below the limit in %d frame(s)%n", c,
                    acc[0] / Math.max(1, assessed), (int) acc[1]));
            System.out.println("passive every  : " + pipeline.passiveEvery() + " frame(s); mean raw p(live) = "
                    + (passiveN == 0 ? "n/a" : String.format("%.3f over %d scores (UNCALIBRATED)", passiveSum / passiveN, passiveN)));
            // Passive only runs on quality-passing frames, which the MockMDS image never is (face too small,
            // overlays). Time it directly on the detected face so #28 has a measurement for every stage.
            pramaan.core.image.RgbImage img = new ImageIoFrameDecoder().decode(new Frame(frames.get(0), PixelFormat.JPEG, 0, 0,
                    Frame.NO_SENSOR_TIMESTAMP, t, 0, null));
            pramaan.core.detect.FaceBox face = detector.detect(img).get(0);
            for (int i = 0; i < 40; i++) {
                long s0 = System.nanoTime();
                passive.liveProbability(img, face);
                pipeline.latency().record("passive(forced)", System.nanoTime() - s0);
            }
            for (String stage : pipeline.latency().stages()) {
                System.out.printf("%-14s : p50 %7.2f ms   p95 %7.2f ms   (n=%d)%n", stage,
                        pipeline.latency().percentileMillis(stage, 50), pipeline.latency().percentileMillis(stage, 95),
                        pipeline.latency().count(stage));
            }
            Runtime rt = Runtime.getRuntime();
            System.gc();
            System.out.printf("java heap      : %.0f MB in use after the run (max %.0f MB); Java heap only, ONNX Runtime keeps the models in native memory%n",
                    (rt.totalMemory() - rt.freeMemory()) / 1048576.0, rt.maxMemory() / 1048576.0);
            System.out.println("cores          : " + rt.availableProcessors());
        }
    }
}
