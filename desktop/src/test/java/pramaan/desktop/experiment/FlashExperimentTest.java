/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.challenge.FlashRunLog;
import pramaan.core.challenge.FlashSafety;

/**
 * The recorder (docs/research-plan-real-data.md, E1). Nothing here touches a camera or a screen: the dry run reads
 * ffmpeg's own test pattern and shows no window.
 */
class FlashExperimentTest {

    private static String run(String... args) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = FlashExperiment.run(args, new PrintStream(buf, true, StandardCharsets.UTF_8));
        return code + ":" + buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theSplitterCutsBackToBackJpegsAndDropsATruncatedLastOne() throws Exception {
        byte[] a = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, 0x00, 4, (byte) 0xFF, (byte) 0xD9};
        byte[] b = {(byte) 0xFF, (byte) 0xD8, 9, 9, (byte) 0xFF, (byte) 0xD9};
        byte[] cut = {(byte) 0xFF, (byte) 0xD8, 7, 7, 7};
        byte[] all = new byte[a.length + 3 + b.length + cut.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(new byte[] {5, 5, 5}, 0, all, a.length, 3); // junk between pictures is skipped
        System.arraycopy(b, 0, all, a.length + 3, b.length);
        System.arraycopy(cut, 0, all, a.length + 3 + b.length, cut.length);
        JpegStreamSplitter s = new JpegStreamSplitter(new ByteArrayInputStream(all));
        assertEquals(List.of(a.length, b.length), List.of(s.next().length, s.next().length));
        assertNull(s.next(), "the truncated picture is dropped");
    }

    @Test
    void aStuffedFFInsideTheDataIsNotTheEndOfThePicture() throws Exception {
        byte[] a = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, (byte) 0xFF, (byte) 0xD0, 1, (byte) 0xFF, (byte) 0xD9};
        assertEquals(a.length, new JpegStreamSplitter(new ByteArrayInputStream(a)).next().length);
    }

    @Test
    void cameraNamesAreReadFromFfmpegsDeviceList() {
        String text = "[dshow @ 000001] DirectShow video devices (some may be both video and audio devices)\n"
                + "[dshow @ 000001]  \"Integrated Camera\" (video)\n"
                + "[dshow @ 000001]     Alternative name \"@device_pnp_xyz\"\n"
                + "[dshow @ 000001]  \"OBS Virtual Camera\" (video)\n"
                + "[dshow @ 000001] DirectShow audio devices\n"
                + "[dshow @ 000001]  \"Microphone\" (audio)\n";
        assertEquals(List.of("Integrated Camera", "OBS Virtual Camera"), FlashExperiment.cameraNames(text));
    }

    @Test
    void theSequenceTheRecorderPlaysIsAlwaysPhotosensitivitySafe() {
        for (int i = 0; i < 200; i++) {
            assertEquals(null, FlashSafety.violation(FlashExperiment.sequence()));
        }
    }

    @Test
    void aRealCameraIsNeverRecordedWithoutSaidConsent(@TempDir Path dir) throws Exception {
        String r = run("--camera", "Integrated Camera", "--out", dir.resolve("x").toString());
        assertTrue(r.startsWith("2:"), r);
        assertTrue(r.contains("must agree first"), r);
        assertFalse(Files.exists(dir.resolve("x").resolve("frames")), "nothing was created, nothing was recorded");
    }

    @Test
    void withoutAnOutputFolderOrACameraItSaysHowToUseIt() throws Exception {
        assertTrue(run().startsWith("2:usage"));
        assertTrue(run("--out", "x").startsWith("2:usage"));
    }

    @Test
    void aDryRunOnATestPatternWritesFramesAnIndexAndALogWithNoWindow(@TempDir Path dir) throws Exception {
        assumeTrue(FlashExperiment.ffmpegAvailable(), "no ffmpeg on this machine");
        Path out = dir.resolve("dry");
        String r = run("--no-window", "--source-args", "-re -f lavfi -i testsrc=size=320x240:rate=15", "--label", "dry", "--out",
                out.toString(), "--lead-ms", "300", "--tail-ms", "300");
        assertTrue(r.startsWith("0:"), r);
        FlashRunLog log = FlashRunLog.fromJson(Files.readString(out.resolve("run.json")));
        assertEquals("dry", log.label());
        assertFalse(log.windowShown(), "a dry run shows nothing to anyone");
        assertTrue(log.steps().size() >= 3, "steps " + log.steps().size());
        List<String> rows = Files.readAllLines(out.resolve("frames.csv"));
        assertEquals("index,epochMs,file", rows.get(0));
        assertTrue(rows.size() > 20, "frames " + (rows.size() - 1));
        long previous = 0;
        for (String row : rows.subList(1, rows.size())) {
            String[] f = row.split(",");
            assertTrue(Files.exists(out.resolve("frames").resolve(f[2])), f[2]);
            assertTrue(Long.parseLong(f[1]) >= previous, "arrival times never go backwards");
            previous = Long.parseLong(f[1]);
        }
        long firstFrame = Long.parseLong(rows.get(1).split(",")[1]);
        assertTrue(log.firstColourAtEpochMs() >= firstFrame, "frames were arriving before the first colour: a dark reference");
    }
}
