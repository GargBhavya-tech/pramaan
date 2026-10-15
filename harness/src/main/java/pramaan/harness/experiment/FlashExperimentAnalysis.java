/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.experiment;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashRunLog;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.FaceRegions;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.FlashReflectionCue;
import pramaan.core.pad.FlashTrace;

/**
 * Asks the real flash cue whether the face in a recording answered the light (docs/research-plan-real-data.md, E1).
 * Reads the folders {@code FlashExperiment} wrote: for each, the frames and the host time each arrived, and the run log
 * of what the screen showed and when. Frames are thinned to a regular rate ({@code fps}, default 10, the rate the cue
 * is designed for), the face is found with the engine's own detector and sampled with the engine's own regions
 * ({@link FaceRegions}), and the cue runs on exactly the trace the engine would have built.
 *
 * <p>Reported per run: how many frames had a face, the correlation rho, the fitted lag in frames and milliseconds (what
 * the screen and camera together added), the nose-to-cheek gain ratio, and the cue's verdict or the reason it abstained.
 * Across runs of the same label: the spread of rho and the verdict against the plan's predictions.
 *
 * <p>What it does <strong>not</strong> do: estimate an error rate. A handful of runs per label cannot. It says whether the
 * physics separates a live face from a replay on this camera and screen, once, and where it did not, why.
 *
 * <pre>./gradlew :harness:analyseFlash --args="--dir build/e1 [--fps 10] [--latency-ms 0] [--models models]"</pre>
 */
public final class FlashExperimentAnalysis {

    /** Finds the face regions of one frame, or nothing if there is no face. The real one is {@link YuNetRegions}. */
    public interface RegionSource {
        /** @return {@code {skin, nose, cheeks}} mean RGB (0-255), or empty if no face was found */
        Optional<double[][]> regions(RgbImage frame, long arrivedAtEpochMs);
    }

    /** What one run said. */
    public static final class RunResult {
        public final String name;
        public final String label;
        public final int framesRecorded;
        public final int framesUsed;
        public final int facesFound;
        public final FlashReflectionCue.Analysis analysis;
        public final double lagMs;
        public final boolean windowShown;

        RunResult(String name, String label, int framesRecorded, int framesUsed, int facesFound,
                FlashReflectionCue.Analysis analysis, double lagMs, boolean windowShown) {
            this.name = name;
            this.label = label;
            this.framesRecorded = framesRecorded;
            this.framesUsed = framesUsed;
            this.facesFound = facesFound;
            this.analysis = analysis;
            this.lagMs = lagMs;
            this.windowShown = windowShown;
        }

        public boolean abstained() {
            return analysis.result.abstained();
        }

        /** The correlation, or NaN if the cue abstained before computing one. */
        public double rho() {
            return analysis.rho;
        }
    }

    private FlashExperimentAnalysis() {
    }

    /**
     * @param fps the rate to thin the recording to
     * @param latencyMs time the screen and camera together are assumed to add; the colour credited to a frame is the one
     *     shown this long before it arrived (0 leaves it to the cue's lag search of up to two frames)
     */
    public static RunResult analyse(Path run, int fps, long latencyMs, RegionSource source) throws IOException {
        FlashRunLog log = FlashRunLog.fromJson(Files.readString(run.resolve("run.json"), StandardCharsets.UTF_8));
        List<long[]> frames = new ArrayList<>(); // {index, epochMs}
        List<String> files = new ArrayList<>();
        List<String> rows = Files.readAllLines(run.resolve("frames.csv"), StandardCharsets.UTF_8);
        for (String row : rows.subList(1, rows.size())) {
            if (row.isBlank()) {
                continue;
            }
            String[] f = row.split(",");
            frames.add(new long[] {Long.parseLong(f[0]), Long.parseLong(f[1])});
            files.add(f[2]);
        }
        List<Integer> chosen = thin(frames, fps);
        FlashTrace trace = new FlashTrace();
        int faces = 0;
        for (int k : chosen) {
            RgbImage img = read(run.resolve("frames").resolve(files.get(k)));
            Optional<double[][]> regions = source.regions(img, frames.get(k)[1]);
            if (regions.isEmpty()) {
                continue;
            }
            faces++;
            double[][] r = regions.get();
            Optional<FlashColour> shown = log.colourAt(frames.get(k)[1] - latencyMs);
            trace.add(shown, r[0], r[1], r[2]);
        }
        FlashReflectionCue.Analysis a = new FlashReflectionCue().analyse(trace);
        return new RunResult(run.getFileName().toString(), log.label(), frames.size(), chosen.size(), faces, a,
                a.lag * 1000.0 / fps, log.windowShown());
    }

    /** Indices of the frames nearest a regular grid at {@code fps}, from the first frame to the last, no frame twice. */
    static List<Integer> thin(List<long[]> frames, int fps) {
        List<Integer> out = new ArrayList<>();
        if (frames.isEmpty()) {
            return out;
        }
        long step = 1000L / fps;
        int j = 0;
        int last = -1;
        for (long t = frames.get(0)[1]; t <= frames.get(frames.size() - 1)[1]; t += step) {
            while (j + 1 < frames.size() && Math.abs(frames.get(j + 1)[1] - t) <= Math.abs(frames.get(j)[1] - t)) {
                j++;
            }
            if (j != last) {
                out.add(j);
                last = j;
            }
        }
        return out;
    }

    private static RgbImage read(Path jpeg) throws IOException {
        BufferedImage in = ImageIO.read(jpeg.toFile());
        if (in == null) {
            throw new IOException("not an image: " + jpeg);
        }
        byte[] px = new byte[in.getWidth() * in.getHeight() * 3];
        int k = 0;
        for (int y = 0; y < in.getHeight(); y++) {
            for (int x = 0; x < in.getWidth(); x++) {
                int p = in.getRGB(x, y);
                px[k++] = (byte) (p >> 16);
                px[k++] = (byte) (p >> 8);
                px[k++] = (byte) p;
            }
        }
        return RgbImage.of(in.getWidth(), in.getHeight(), px);
    }

    /** The engine's own face detector and regions. */
    public static final class YuNetRegions implements RegionSource, AutoCloseable {
        private final YuNetDetector detector;

        public YuNetRegions(Path yuNetModel) throws Exception {
            this.detector = new YuNetDetector(OnnxModel.load(Files.readAllBytes(yuNetModel)), 0.8f, 0.3f);
        }

        @Override
        public Optional<double[][]> regions(RgbImage frame, long arrivedAtEpochMs) {
            try {
                return detector.detect(frame).stream().max(Comparator.comparingDouble(f -> f.width() * f.height()))
                        .map(f -> FaceRegions.sample(frame, f));
            } catch (Exception e) {
                throw new IllegalStateException("face detection failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() throws ai.onnxruntime.OrtException {
            detector.close();
        }
    }

    // ------------------------------------------------------------------------------------------ the report

    /** One line per run, then per label the spread and the verdict against the plan's predictions. */
    public static String report(List<RunResult> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-14s %-9s %6s %6s %7s %5s %7s %6s  %s%n", "run", "label", "frames", "faces", "rho", "lag",
                "lag ms", "n/c", "verdict"));
        for (RunResult r : runs) {
            CueResult c = r.analysis.result;
            sb.append(String.format("%-14s %-9s %6d %6d %7s %5s %7s %6s  %s%s%n", r.name, r.label, r.framesUsed,
                    r.facesFound, Double.isNaN(r.rho()) ? "-" : String.format("%.2f", r.rho()), r.analysis.lag < 0 ? "-" : r.analysis.lag,
                    r.analysis.lag < 0 ? "-" : String.format("%.0f", r.lagMs),
                    Double.isNaN(r.analysis.noseToCheekGain) ? "-" : String.format("%.2f", r.analysis.noseToCheekGain),
                    c.abstained() ? "abstained: " + c.abstainReason() : String.format("pLive %.2f", c.pLive()),
                    r.windowShown ? "" : "   (DRY RUN: no light was shown)"));
        }
        Map<String, List<RunResult>> byLabel = runs.stream().collect(Collectors.groupingBy(r -> r.label,
                LinkedHashMap::new, Collectors.toList()));
        sb.append('\n');
        for (Map.Entry<String, List<RunResult>> e : byLabel.entrySet()) {
            List<Double> rhos = e.getValue().stream().filter(r -> !Double.isNaN(r.rho())).map(RunResult::rho).sorted()
                    .collect(Collectors.toList());
            long abstained = e.getValue().stream().filter(RunResult::abstained).count();
            sb.append(String.format("%-9s runs %d, abstained %d, rho %s%n", e.getKey(), e.getValue().size(), abstained,
                    rhos.isEmpty() ? "none" : String.format("min %.2f  median %.2f  max %.2f", rhos.get(0),
                            rhos.get(rhos.size() / 2), rhos.get(rhos.size() - 1))));
        }
        sb.append(verdict(byLabel));
        return sb.toString();
    }

    private static String verdict(Map<String, List<RunResult>> byLabel) {
        List<RunResult> genuine = byLabel.getOrDefault("genuine", List.of());
        List<RunResult> replay = byLabel.getOrDefault("replay", List.of());
        StringBuilder sb = new StringBuilder("\nAgainst the plan's predictions (Bible Part 8.6; docs/research-plan-real-data.md):\n");
        if (genuine.isEmpty() || replay.isEmpty()) {
            return sb.append("  needs runs labelled both 'genuine' and 'replay' to say anything about separation.\n").toString();
        }
        long genuineAbstained = genuine.stream().filter(RunResult::abstained).count();
        if (genuineAbstained * 2 > genuine.size()) {
            sb.append("  FALSIFIED in the cheap direction: the cue abstained on most genuine runs, so this screen and room do not"
                    + " give it enough light. A dedicated light, a darker room or a closer seat is needed; this is not a failure of"
                    + " the physics.\n");
        }
        double minGenuine = genuine.stream().filter(r -> !r.abstained()).mapToDouble(RunResult::rho).min().orElse(Double.NaN);
        double maxReplay = replay.stream().filter(r -> !r.abstained()).mapToDouble(RunResult::rho).max().orElse(Double.NaN);
        if (Double.isNaN(minGenuine) || Double.isNaN(maxReplay)) {
            sb.append("  not enough non-abstained runs on one side to compare.\n");
        } else {
            sb.append(String.format("  weakest genuine rho %.2f (predicted about 0.8, pass at %.2f); strongest replay rho %.2f"
                    + " (predicted below 0.3)%n", minGenuine, FlashReflectionCue.PASS_RHO, maxReplay));
            sb.append(minGenuine > maxReplay
                    ? "  SEPARATED: every genuine run correlated more than every replay run.\n"
                    : "  NOT SEPARATED: the ranges overlap. Either the physics does not work on this camera and screen, or the"
                            + " recording was bad (face not detected, screen too dim, camera auto-exposure fighting the flash).\n");
        }
        sb.append(String.format("  %d genuine and %d replay runs: this shows whether the physics works once. It cannot give an"
                + " APCER or BPCER (0 of 10 still allows a true rate above 25%%).%n", genuine.size(), replay.size()));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ the command

    public static void main(String[] args) throws Exception {
        List<Path> runs = new ArrayList<>();
        int fps = 10;
        long latency = 0;
        Path models = Path.of("models");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run":
                    runs.add(Path.of(args[++i]));
                    break;
                case "--dir":
                    try (Stream<Path> s = Files.list(Path.of(args[++i]))) {
                        s.filter(p -> Files.exists(p.resolve("run.json"))).sorted().forEach(runs::add);
                    }
                    break;
                case "--fps":
                    fps = Integer.parseInt(args[++i]);
                    break;
                case "--latency-ms":
                    latency = Long.parseLong(args[++i]);
                    break;
                case "--models":
                    models = Path.of(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (runs.isEmpty()) {
            System.err.println("usage: --dir <folder of runs> or --run <run folder>... [--fps 10] [--latency-ms 0] [--models models]");
            System.exit(2);
        }
        List<RunResult> results = new ArrayList<>();
        try (YuNetRegions source = new YuNetRegions(models.resolve("face_detection_yunet_2023mar.onnx"))) {
            for (Path run : runs) {
                results.add(analyse(run, fps, latency, source));
            }
        }
        System.out.print(report(results));
    }
}
