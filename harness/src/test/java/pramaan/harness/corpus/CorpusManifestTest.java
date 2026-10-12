/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.testing.TestClock;
import pramaan.harness.mock.MockFrameSource;

/** Build Map #16: "play a glossy-print attack at 3 fps in dim light" — and nothing else. */
class CorpusManifestTest {

    @TempDir
    Path dir;

    private Path clip(String name, int frames) throws IOException {
        Path d = Files.createDirectories(dir.resolve(name));
        for (int i = 0; i < frames; i++) {
            Files.write(d.resolve(String.format("%03d.jpg", i)), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) i});
        }
        return d;
    }

    private Path manifest(String... rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(CorpusManifest.HEADER);
        lines.addAll(List.of(rows));
        return Files.write(dir.resolve("manifest.csv"), lines, StandardCharsets.UTF_8);
    }

    @Test
    void selectsExactlyTheRequestedSpeciesAndConditionAndReplaysItAtThreeFps() throws Exception {
        clip("p01-good", 3);
        clip("glossy-dim", 5);
        clip("glossy-good", 5);
        clip("matte-dim", 5);
        CorpusManifest m = CorpusManifest.load(manifest(
                "p01-good,bona_fide,,P01,good,none,webcam-a,30",
                "glossy-dim,attack,print-glossy-4x6,P01,dim,none,webcam-a,30",
                "glossy-good,attack,print-glossy-4x6,P01,good,none,webcam-a,30",
                "matte-dim,attack,print-matte-a4,P02,dim,glasses,webcam-b,30"));

        List<CorpusManifest.Entry> hits = m.select(CorpusManifest.Label.ATTACK, "print-glossy-4x6", "dim");
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).clip().endsWith("glossy-dim"));
        assertEquals(List.of("P01", "P02"), new ArrayList<>(m.people()));

        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        MockFrameSource source = MockFrameSource.builder(clock).delayer(clock::advance).fps(3)
                .neutral(ClipLoader.load(hits.get(0).clip())).build();
        List<Frame> frames = new ArrayList<>();
        source.start(null, new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                frames.add(frame);
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
            }
        });
        for (int i = 0; i < 5; i++) {
            source.step();
        }
        assertEquals(333, Duration.between(frames.get(0).hostArrival(), frames.get(1).hostArrival()).toMillis());
        assertEquals(4, frames.get(4).copyBytes()[2], "the fifth frame of the glossy-dim clip");
    }

    @Test
    void mislabelledRowsFailWithTheirLineNumbers() throws Exception {
        clip("c", 1);
        IOException e = assertThrows(IOException.class, () -> CorpusManifest.load(manifest(
                "c,attack,print,P01,good,none,w,30",
                "c,attack,,P01,good,none,w,30",
                "c,bona_fide,print-matte-a4,P01,good,none,w,30",
                "c,bona_fide,,Ravi,good,none,w,30",
                "c,bona_fide,,P01,sunny,none,w,30",
                "missing,bona_fide,,P01,good,none,w,30")));
        for (int line = 2; line <= 7; line++) {
            assertTrue(e.getMessage().contains("line " + line + ":"), "line " + line + " not reported:\n" + e.getMessage());
        }
        assertTrue(e.getMessage().contains("never a name"));
    }

    @Test
    void theRealMockStreamLoadsAsAClip() throws Exception {
        Path sample = Path.of("..", "sbi", "testdata", "stream-sample.mjpeg");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(sample), "stream fixture absent");
        assertTrue(ClipLoader.load(sample).size() >= 60);
    }
}
