/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashRunLog;

/**
 * The analyser (docs/research-plan-real-data.md, E1) on SYNTHETIC recordings: a face that answers the light, one that does
 * not (a replay), and a known screen-plus-camera lag. Proves the pairing, thinning and reporting; says nothing about a
 * real face, which is what the experiment is for.
 */
class FlashExperimentAnalysisTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final FlashColour[] ORDER = {FlashColour.GREEN, FlashColour.BLUE, FlashColour.MAGENTA, FlashColour.YELLOW,
        FlashColour.CYAN, FlashColour.WHITE, FlashColour.BLUE, FlashColour.GREEN};
    private static final double[] AMBIENT = {120, 90, 80};

    /** A run folder: 30 fps for 6 s, eight 400 ms colour steps from T0+800, tiny JPEG frames. */
    private static Path makeRun(Path dir, String name, String label, boolean windowShown) throws IOException {
        Path run = dir.resolve(name);
        Files.createDirectories(run.resolve("frames"));
        List<FlashRunLog.Step> steps = new ArrayList<>();
        for (int i = 0; i < ORDER.length; i++) {
            steps.add(new FlashRunLog.Step(ORDER[i], T0 + 800 + 400L * i, 400));
        }
        FlashRunLog log = new FlashRunLog(label, "synthetic", T0, steps, T0 + 800 + 400L * ORDER.length, windowShown);
        Files.write(run.resolve("run.json"), log.toJson().getBytes(StandardCharsets.UTF_8));
        BufferedImage tiny = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        StringBuilder csv = new StringBuilder("index,epochMs,file\n");
        for (int i = 1; i <= 180; i++) {
            String file = String.format("%06d.jpg", i);
            ImageIO.write(tiny, "jpg", run.resolve("frames").resolve(file).toFile());
            csv.append(i).append(',').append(T0 + 33L * i).append(',').append(file).append('\n');
        }
        Files.write(run.resolve("frames.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
        return run;
    }

    /**
     * A face that answers the light: the ambient colour plus a share of what the screen showed {@code latencyMs} earlier,
     * the nose answering more strongly than the cheeks. {@code gain} 0 is a replay: it never hears the light.
     */
    private static FlashExperimentAnalysis.RegionSource face(FlashRunLog log, double gain, long latencyMs) {
        Random noise = new Random(3);
        return (frame, at) -> {
            Optional<FlashColour> c = log.colourAt(at - latencyMs);
            double[] e = c.map(x -> new double[] {x.r(), x.g(), x.b()}).orElse(new double[3]);
            return Optional.of(new double[][] {region(e, gain, 1.0, noise), region(e, gain, 1.5, noise),
                region(e, gain, 1.0, noise)});
        };
    }

    private static double[] region(double[] emitted, double gain, double strength, Random noise) {
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            out[k] = AMBIENT[k] + gain * strength * emitted[k] * (AMBIENT[k] / 100) + noise.nextGaussian() * 0.3;
        }
        return out;
    }

    private static FlashRunLog logOf(Path run) throws IOException {
        return FlashRunLog.fromJson(Files.readString(run.resolve("run.json")));
    }

    @Test
    void framesAreThinnedToARegularRateWithNoFrameTwice() {
        List<long[]> frames = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            frames.add(new long[] {i, T0 + 33L * i});
        }
        List<Integer> ten = FlashExperimentAnalysis.thin(frames, 10);
        assertEquals(100, ten.size(), 5);
        for (int i = 1; i < ten.size(); i++) {
            assertTrue(ten.get(i) > ten.get(i - 1), "strictly increasing");
        }
        assertEquals(3.0, (double) (ten.get(50) - ten.get(40)) / 10, 0.4, "about every third frame at 30 fps");
        assertTrue(FlashExperimentAnalysis.thin(List.of(), 10).isEmpty());
    }

    @Test
    void aFaceThatAnswersTheLightCorrelatesAndAReplayThatNeverHearsItDoesNot(@TempDir Path dir) throws Exception {
        Path genuine = makeRun(dir, "g01", "genuine", true);
        Path replay = makeRun(dir, "r01", "replay", true);
        FlashExperimentAnalysis.RunResult g = FlashExperimentAnalysis.analyse(genuine, 10, 0,
                face(logOf(genuine), 25, 60));
        FlashExperimentAnalysis.RunResult r = FlashExperimentAnalysis.analyse(replay, 10, 0, face(logOf(replay), 0, 60));
        assertFalse(g.abstained(), "genuine abstained: " + g.analysis.result.abstainReason());
        assertTrue(g.rho() >= 0.6, "genuine rho " + g.rho());
        assertTrue(g.analysis.result.pLive() > 0.8);
        assertTrue(r.abstained() || r.rho() < 0.3, "replay rho " + r.rho());
        assertTrue(g.facesFound > 40, "faces " + g.facesFound);
    }

    @Test
    void theFittedLagIsTheScreenAndCameraDelayAndItIsReportedInMilliseconds(@TempDir Path dir) throws Exception {
        Path run = makeRun(dir, "g", "genuine", true);
        FlashExperimentAnalysis.RunResult none = FlashExperimentAnalysis.analyse(run, 10, 0, face(logOf(run), 25, 0));
        FlashExperimentAnalysis.RunResult late = FlashExperimentAnalysis.analyse(run, 10, 0, face(logOf(run), 25, 200));
        assertTrue(late.analysis.lag > none.analysis.lag, "lag " + none.analysis.lag + " -> " + late.analysis.lag);
        assertEquals(late.analysis.lag * 100.0, late.lagMs, 1e-9);
    }

    @Test
    void declaringTheLatencyShiftsWhichColourEachFrameIsCreditedWith(@TempDir Path dir) throws Exception {
        Path run = makeRun(dir, "g", "genuine", true);
        FlashExperimentAnalysis.RunResult blind = FlashExperimentAnalysis.analyse(run, 10, 0, face(logOf(run), 25, 300));
        FlashExperimentAnalysis.RunResult told = FlashExperimentAnalysis.analyse(run, 10, 300, face(logOf(run), 25, 300));
        assertTrue(told.rho() >= blind.rho() - 1e-9, "telling it the delay does not make the fit worse");
    }

    @Test
    void theReportSaysSeparatedWhenEveryGenuineRunBeatsEveryReplay(@TempDir Path dir) throws Exception {
        List<FlashExperimentAnalysis.RunResult> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path g = makeRun(dir, "g0" + i, "genuine", true);
            Path r = makeRun(dir, "r0" + i, "replay", true);
            all.add(FlashExperimentAnalysis.analyse(g, 10, 0, face(logOf(g), 25, 60)));
            all.add(FlashExperimentAnalysis.analyse(r, 10, 0, face(logOf(r), 0, 60)));
        }
        String report = FlashExperimentAnalysis.report(all);
        assertTrue(report.contains("SEPARATED"), report);
        assertFalse(report.contains("NOT SEPARATED"), report);
        assertTrue(report.contains("cannot give an APCER or BPCER"), "it must not pretend to measure an error rate");
    }

    @Test
    void theReportSaysNotSeparatedWhenTheReplayAnswersTheLightToo(@TempDir Path dir) throws Exception {
        List<FlashExperimentAnalysis.RunResult> all = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Path g = makeRun(dir, "g0" + i, "genuine", true);
            Path r = makeRun(dir, "r0" + i, "replay", true);
            all.add(FlashExperimentAnalysis.analyse(g, 10, 0, face(logOf(g), 25, 60)));
            all.add(FlashExperimentAnalysis.analyse(r, 10, 0, face(logOf(r), 25, 60))); // an injected feed that sees the light
        }
        assertTrue(FlashExperimentAnalysis.report(all).contains("NOT SEPARATED"));
    }

    @Test
    void aReportWithOnlyOneLabelSaysItCannotJudgeSeparation(@TempDir Path dir) throws Exception {
        Path g = makeRun(dir, "g", "genuine", true);
        String report = FlashExperimentAnalysis.report(List.of(FlashExperimentAnalysis.analyse(g, 10, 0,
                face(logOf(g), 25, 60))));
        assertTrue(report.contains("needs runs labelled both"), report);
    }

    @Test
    void aDryRunIsFlaggedAsSoSoNobodyMistakesItForAMeasurement(@TempDir Path dir) throws Exception {
        Path g = makeRun(dir, "dry", "dry", false);
        String report = FlashExperimentAnalysis.report(List.of(FlashExperimentAnalysis.analyse(g, 10, 0,
                face(logOf(g), 25, 60))));
        assertTrue(report.contains("DRY RUN"), report);
    }

    @Test
    void whenTheCameraNeverSawAFaceTheCueAbstainsInsteadOfInventingAResult(@TempDir Path dir) throws Exception {
        Path g = makeRun(dir, "nf", "genuine", true);
        FlashExperimentAnalysis.RunResult r = FlashExperimentAnalysis.analyse(g, 10, 0, (f, t) -> Optional.empty());
        assertEquals(0, r.facesFound);
        assertTrue(r.abstained());
    }

    /** The real detector on a real photograph of a face, when the models and the sample are on this machine. */
    @Test
    void theEnginesOwnDetectorFindsARealFaceAndSamplesThreeRegions() throws Exception {
        Path model = Path.of("..", "models", "face_detection_yunet_2023mar.onnx");
        Path photo = Path.of("..", "third_party", "mosip-mock-services", "MockMDS", "Biometric Devices", "Face", "Stream Image",
                "0.jpeg");
        assumeTrue(Files.exists(model) && Files.exists(photo), "models or the sample photograph are not on this machine");
        BufferedImage in = ImageIO.read(photo.toFile());
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
        try (FlashExperimentAnalysis.YuNetRegions source = new FlashExperimentAnalysis.YuNetRegions(model)) {
            Optional<double[][]> regions = source.regions(pramaan.core.image.RgbImage.of(in.getWidth(), in.getHeight(), px), 0);
            assertTrue(regions.isPresent(), "a face in a real photograph is found");
            assertEquals(3, regions.get().length);
            for (double[] region : regions.get()) {
                assertTrue(region[0] > 20 && region[0] < 250, "a plausible skin tone, red " + region[0]);
            }
        }
    }
}
