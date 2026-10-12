/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.image.RgbImage;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;
import pramaan.desktop.ImageIoFrameDecoder;
import pramaan.harness.Models;
import pramaan.harness.corpus.ClipLoader;
import pramaan.harness.corpus.CorpusManifest;

/**
 * Build Map #66: {@code tools/degrade.py} makes rural-camera copies that the harness reads as ordinary corpus
 * clips — a bona fide clip at CRF 38 still parses and scores, and the derived manifest grows by one row per
 * variant that applies (a 360-pixel clip is never "degraded" upwards to 480p).
 */
class DegradeToolTest {

    private static final Path MODELS = Path.of("..", "models");

    private static boolean available(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void aCrf38CopyStillParsesAndScoresAndTheManifestGrows(@TempDir Path dir) throws Exception {
        assumeTrue(available("python", "--version"), "python not on PATH");
        assumeTrue(available("ffmpeg", "-version"), "ffmpeg not on PATH");
        assumeTrue(Models.present(MODELS), "models/ not downloaded (gitignored)");
        assumeTrue(Files.exists(Path.of("..", "sbi", "testdata", "stream-sample.mjpeg")),
                "MockMDS stream fixture absent (gitignored: it shows a real person)");

        byte[] mjpeg = Files.readAllBytes(Path.of("..", "sbi", "testdata", "stream-sample.mjpeg"));
        Path clip = dir.resolve("clips/face");
        Files.createDirectories(clip);
        int at = 0;
        for (int n = 0; n < 10; n++) {
            int start = indexOf(mjpeg, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, at);
            int end = indexOf(mjpeg, new byte[] {(byte) 0xFF, (byte) 0xD9}, start) + 2;
            Files.write(clip.resolve(String.format("%03d.jpg", n)), java.util.Arrays.copyOfRange(mjpeg, start, end));
            at = end;
        }
        Path manifest = dir.resolve("manifest.csv");
        Files.writeString(manifest, CorpusManifest.HEADER + "\nclips/face,bona_fide,,P01,good,none,mockmds,10\n");
        Path out = dir.resolve("derived");

        Process p = new ProcessBuilder("python", Path.of("..", "tools", "degrade.py").toString(), "--manifest",
                manifest.toString(), "--out", out.toString(), "--variants", "crf38,noise,flicker50,480p")
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), log);
        assertEquals(0, p.exitValue(), log);

        CorpusManifest derived = CorpusManifest.load(out.resolve("manifest-derived.csv"));
        List<String> devices = new ArrayList<>();
        derived.entries().forEach(e -> devices.add(e.device()));
        assertEquals(List.of("mockmds~crf38", "mockmds~noise", "mockmds~flicker50"), devices,
                "one row per variant that applies; 480p would be an upscale of this clip");
        for (CorpusManifest.Entry e : derived.entries()) {
            assertEquals("P01", e.person(), "the person id is kept, so a split by person carries over");
            assertEquals(CorpusManifest.Label.BONA_FIDE, e.label());
            assertEquals(10, ClipLoader.load(e.clip()).size(), e.device() + " keeps every frame");
        }

        CorpusManifest.Entry crf38 = derived.entries().get(0);
        ImageIoFrameDecoder decoder = new ImageIoFrameDecoder();
        try (YuNetDetector yunet = Models.detector(MODELS); PassiveScorer passive = Models.passive(MODELS)) {
            int scored = 0;
            for (byte[] jpeg : ClipLoader.load(crf38.clip())) {
                RgbImage img = decoder.decode(new Frame(jpeg, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                        java.time.Instant.EPOCH, 0, null));
                List<FaceBox> faces = yunet.detect(img);
                if (faces.size() == 1) {
                    double p0 = passive.liveProbability(img, faces.get(0));
                    assertTrue(p0 >= 0 && p0 <= 1, "a probability: " + p0);
                    scored++;
                }
            }
            assertTrue(scored >= 8, "the face is still found and scored at CRF 38 in " + scored + " of 10 frames");
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException("pattern not found");
    }
}
