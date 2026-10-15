/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.experiment;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashRunLog;
import pramaan.core.challenge.FlashSafety;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FlashLight;
import pramaan.core.source.SessionCapabilities;
import pramaan.desktop.capture.FlashWindow;

/**
 * Recorder for the first real-data experiment (docs/research-plan-real-data.md, E1): plays the engine's own random flash
 * sequence on a screen while a camera records, and writes what it needs for {@code FlashExperimentAnalysis} to ask the real
 * flash cue whether the face answered the light.
 *
 * <pre>
 * ./gradlew :desktop:flashExperiment --args="--list-cameras"
 * ./gradlew :desktop:flashExperiment --args="--camera 'Integrated Camera' --label genuine --out build/e1/g01 --consent-given"
 * ./gradlew :desktop:flashExperiment --args="--camera 'Integrated Camera' --label replay  --out build/e1/r01 --consent-given"
 * </pre>
 *
 * <p>How the pairing works: {@code ffmpeg} reads the camera and writes JPEG frames to a pipe; each frame is stamped with
 * the host clock <em>as it arrives here</em>, which is what the engine does for an SBI stream ({@code hostArrival}); and
 * each colour is stamped when this program asked the screen to show it. Both use the same clock, so nothing depends on
 * the timestamps inside a video container.
 *
 * <p>Output, in the {@code --out} folder (under {@code build/}, which is gitignored): {@code frames/NNNNNN.jpg},
 * {@code frames.csv} ({@code index,epochMs,file}) and {@code run.json} ({@link FlashRunLog}). The frames are
 * <strong>biometric data of whoever sat in front of the camera</strong>: it records only with {@code --consent-given},
 * and the person can ask for the folder to be deleted at any time.
 *
 * <p>Photosensitivity: the sequence passes {@link FlashSafety} (at most 3 colour changes a second, no saturated red,
 * steps of at least 333 ms) before a single colour is shown, and the person can close their eyes, look away or press
 * Ctrl+C at any time. {@code --no-window} runs everything except the screen, for a dry run.
 */
public final class FlashExperiment {

    private static final int DEFAULT_LEAD_MS = 700;
    private static final int DEFAULT_TAIL_MS = 800;

    private FlashExperiment() {
    }

    public static void main(String[] args) throws Exception {
        int code = run(args, System.out);
        Platform.exit();
        System.exit(code);
    }

    /** @return the process exit code: 0 on a recorded run */
    public static int run(String[] args, PrintStream out) throws Exception {
        Path folder = null;
        String label = "unlabelled";
        String camera = null;
        String sourceArgs = null;
        boolean list = false;
        boolean consent = false;
        boolean noWindow = false;
        int screen = 0;
        int width = 640;
        int height = 480;
        int fps = 30;
        int lead = DEFAULT_LEAD_MS;
        int tail = DEFAULT_TAIL_MS;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out":
                    folder = Path.of(args[++i]);
                    break;
                case "--label":
                    label = args[++i];
                    break;
                case "--camera":
                    camera = args[++i];
                    break;
                case "--source-args":
                    sourceArgs = args[++i];
                    break;
                case "--list-cameras":
                    list = true;
                    break;
                case "--consent-given":
                    consent = true;
                    break;
                case "--no-window":
                    noWindow = true;
                    break;
                case "--screen":
                    screen = args[++i].equals("primary") ? 0 : Integer.parseInt(args[i]);
                    break;
                case "--size":
                    String[] wh = args[++i].split("x");
                    width = Integer.parseInt(wh[0]);
                    height = Integer.parseInt(wh[1]);
                    break;
                case "--fps":
                    fps = Integer.parseInt(args[++i]);
                    break;
                case "--lead-ms":
                    lead = Integer.parseInt(args[++i]);
                    break;
                case "--tail-ms":
                    tail = Integer.parseInt(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (list) {
            return listCameras(out);
        }
        boolean realCamera = sourceArgs == null;
        if (folder == null || (realCamera && camera == null)) {
            out.println("usage: --out <folder> --camera <name> --label genuine|replay --consent-given"
                    + "   (or --list-cameras; --no-window and --source-args \"-re -f lavfi -i testsrc\" for a dry run)");
            return 2;
        }
        if (realCamera && !consent) {
            out.println("This records the camera. Whoever is in front of it must agree first: re-run with --consent-given"
                    + " once they have. The frames are biometric data; they stay in " + folder
                    + " and can be deleted at any time.");
            return 2;
        }
        FlashSequence sequence = sequence();
        String violation = FlashSafety.violation(sequence);
        if (violation != null) {
            out.println("refusing to show an unsafe flash sequence: " + violation);
            return 3;
        }
        Files.createDirectories(folder.resolve("frames"));
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error"));
        if (realCamera) {
            command.addAll(List.of("-f", "dshow", "-framerate", Integer.toString(fps), "-video_size", width + "x" + height,
                    "-i", "video=" + camera));
        } else {
            command.addAll(Arrays.asList(sourceArgs.trim().split("\\s+")));
        }
        command.addAll(List.of("-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "-"));
        out.println("recording " + (realCamera ? "camera '" + camera + "'" : "dry-run source") + " to " + folder
                + (noWindow ? " (no window: a dry run, nothing is shown on any screen)" : ""));
        if (!noWindow) {
            out.println("The screen will flash colours for about " + Math.round((lead + total(sequence) + tail) / 1000.0)
                    + " seconds (never more than 3 changes a second). Look at the screen as normal; close your eyes or"
                    + " look away at any time.");
        }

        Process ffmpeg = new ProcessBuilder(command).redirectError(folder.resolve("ffmpeg.log").toFile()).start();
        List<String> index = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger frames = new AtomicInteger();
        CountDownLatch firstFrame = new CountDownLatch(1);
        Path framesDir = folder.resolve("frames");
        Thread reader = new Thread(() -> {
            try {
                JpegStreamSplitter split = new JpegStreamSplitter(ffmpeg.getInputStream());
                byte[] jpeg;
                while ((jpeg = split.next()) != null) {
                    long arrived = System.currentTimeMillis();
                    int n = frames.incrementAndGet();
                    String name = String.format("%06d.jpg", n);
                    Files.write(framesDir.resolve(name), jpeg);
                    index.add(n + "," + arrived + "," + name);
                    firstFrame.countDown();
                }
            } catch (IOException e) {
                out.println("reading the camera stopped: " + e.getMessage());
            }
        }, "flash-experiment-reader");
        reader.setDaemon(true);
        long startedAt = System.currentTimeMillis();
        reader.start();
        try {
            if (!firstFrame.await(15, TimeUnit.SECONDS)) {
                out.println("no frame from the camera in 15 seconds; see " + folder.resolve("ffmpeg.log"));
                return 4;
            }
            Thread.sleep(lead); // dark: the ambient reference the cue measures against
            FlashLight light = noWindow ? new NoLight() : openWindow(screen);
            List<FlashRunLog.Step> steps = new ArrayList<>();
            for (int i = 0; i < sequence.colours().size(); i++) {
                light.show(sequence.colours().get(i));
                steps.add(new FlashRunLog.Step(sequence.colours().get(i), System.currentTimeMillis(),
                        sequence.durationsMs()[i]));
                Thread.sleep(sequence.durationsMs()[i]);
            }
            light.off();
            long offAt = System.currentTimeMillis();
            Thread.sleep(tail);
            ffmpeg.destroy();
            ffmpeg.waitFor(5, TimeUnit.SECONDS);
            reader.join(3000);
            Files.write(folder.resolve("frames.csv"), ("index,epochMs,file\n" + String.join("\n", index) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            FlashRunLog log = new FlashRunLog(label, realCamera ? camera : "dry-run", startedAt, steps, offAt, !noWindow);
            Files.write(folder.resolve("run.json"), log.toJson().getBytes(StandardCharsets.UTF_8));
            out.println("done: " + frames.get() + " frames, " + steps.size() + " colours, " + folder.resolve("run.json"));
            return 0;
        } finally {
            ffmpeg.destroyForcibly();
        }
    }

    private static long total(FlashSequence s) {
        long ms = 0;
        for (int d : s.durationsMs()) {
            ms += d;
        }
        return ms;
    }

    /** A fresh sequence from the same generator the engine uses. */
    static FlashSequence sequence() {
        DeviceCapabilities caps = new DeviceCapabilities(
                new DeviceMetadata("1", "0", "experiment", DeviceLevel.L0, "experiment", "screen", "1"),
                EnumSet.of(Capability.RESIDENT_DISPLAY));
        return ChallengeGenerator.fresh(new SecureRandom()).plan(
                List.of(ChallengeKind.TURN, ChallengeKind.BLINK, ChallengeKind.SMILE, ChallengeKind.FLASH), 1, 3,
                SessionCapabilities.of(10, caps), AccessibilityAdjustments.NONE).flash().orElseThrow();
    }

    private static FlashLight openWindow(int screen) throws InterruptedException {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException alreadyRunning) {
            up.countDown();
        }
        up.await();
        Platform.setImplicitExit(false);
        return new FlashWindow(screen);
    }

    private static int listCameras(PrintStream out) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
                .redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(20, TimeUnit.SECONDS);
        List<String> names = cameraNames(text);
        if (names.isEmpty()) {
            out.println("no cameras found by ffmpeg (is it installed, and is this Windows?)");
            return 1;
        }
        names.forEach(n -> out.println("camera: " + n));
        return 0;
    }

    /** The video device names in {@code ffmpeg -list_devices} output ({@code "Name" (video)}). */
    static List<String> cameraNames(String ffmpegOutput) {
        List<String> names = new ArrayList<>();
        for (String line : ffmpegOutput.split("\\R")) {
            int q1 = line.indexOf('"');
            int q2 = line.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1 && line.contains("(video)")) {
                names.add(line.substring(q1 + 1, q2));
            }
        }
        return names;
    }

    /** The light for a dry run: nothing is shown on any screen. */
    private static final class NoLight implements FlashLight {
        @Override
        public void show(FlashColour colour) {
        }

        @Override
        public void off() {
        }
    }

    /** True if {@code ffmpeg} can be run from this process. */
    public static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
