/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;
import pramaan.core.testing.TestClock;

/** Build Map #13: incremental M-JPEG parsing, both delimiter styles, truncation, and hostile input. */
class MjpegStreamReaderTest {

    private static final String MOCK_TYPE = "multipart/x-mixed-replace; boundary=--BoundaryString";
    private static final String RFC_TYPE = "multipart/x-mixed-replace; boundary=frame";
    private static final String CRLF = "" + (char) 13 + (char) 10;

    private static byte[] jpeg(int n) {
        byte[] b = new byte[40 + n];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        for (int i = 2; i < b.length - 2; i++) {
            b[i] = (byte) (i * 31 + n);
        }
        b[b.length - 2] = (byte) 0xFF;
        b[b.length - 1] = (byte) 0xD9;
        return b;
    }

    /** Builds a body exactly like MockMDS: delimiter line, "Name:value" headers, blank line, JPEG, CRLF CRLF. */
    private static byte[] body(String delimiter, int frames, boolean contentLength) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) {
            byte[] j = jpeg(i);
            out.write((delimiter + "\r\nContent-Type:image/jpeg\r\n").getBytes(StandardCharsets.US_ASCII));
            if (contentLength) {
                out.write(("Content-Length:" + j.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(j);
            out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static List<Frame> readAll(InputStream in, String type) throws IOException {
        MjpegStreamReader r = new MjpegStreamReader(in, type, TestClock.at("2026-09-23T10:00:00Z"), null);
        List<Frame> frames = new ArrayList<>();
        Frame f;
        while ((f = r.next()) != null) {
            frames.add(f);
        }
        return frames;
    }

    @Test
    void readsTheMockMdsDelimiterQuirk() throws IOException {
        List<Frame> frames = readAll(new ByteArrayInputStream(body("--BoundaryString", 5, true)), MOCK_TYPE);
        assertEquals(5, frames.size());
        assertArrayEquals(jpeg(3), frames.get(3).copyBytes());
        assertEquals(PixelFormat.JPEG, frames.get(0).format());
        assertEquals(4, frames.get(4).index());
    }

    @Test
    void readsStandardRfcDelimitersWithAndWithoutContentLength() throws IOException {
        assertEquals(4, readAll(new ByteArrayInputStream(body("--frame", 4, true)), RFC_TYPE).size());
        // Without Content-Length a part is complete only once the next delimiter arrives, so a properly
        // closed stream ends with the close delimiter...
        byte[] closed = concat(body("--frame", 4, false), ("--frame--" + CRLF).getBytes(StandardCharsets.US_ASCII));
        List<Frame> noLength = readAll(new ByteArrayInputStream(closed), RFC_TYPE);
        assertEquals(4, noLength.size());
        assertArrayEquals(jpeg(2), noLength.get(2).copyBytes(), "separator CR/LF must not leak into the image");
        assertArrayEquals(jpeg(3), noLength.get(3).copyBytes());

        // ...and one that just stops cannot prove its last part is whole: it is reported as truncated.
        MjpegStreamReader open = new MjpegStreamReader(new ByteArrayInputStream(body("--frame", 4, false)), RFC_TYPE,
                TestClock.at("2026-09-23T10:00:00Z"), null);
        int count = 0;
        while (open.next() != null) {
            count++;
        }
        assertEquals(3, count);
        assertTrue(open.truncated());
    }

    @Test
    void aTruncatedFinalFrameEndsQuietly() throws IOException {
        byte[] full = body("--BoundaryString", 3, true);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 20);
        MjpegStreamReader r = new MjpegStreamReader(new ByteArrayInputStream(cut), MOCK_TYPE,
                TestClock.at("2026-09-23T10:00:00Z"), null);
        assertTrue(r.next() != null && r.next() != null);
        assertNull(r.next());
        assertTrue(r.truncated());
        assertNull(r.next(), "stays finished");
    }

    @Test
    void anAbsurdContentLengthIsRejectedNotAllocated() {
        byte[] hostile = ("--frame\r\nContent-Length:999999999\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> readAll(new ByteArrayInputStream(hostile), RFC_TYPE));
    }

    @Test
    void aMissingBoundaryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MjpegStreamReader(new ByteArrayInputStream(new byte[0]), "multipart/x-mixed-replace",
                        TestClock.at("2026-09-23T10:00:00Z"), null));
    }

    @Test
    void theRealMockStreamParsesEveryFrame() throws IOException {
        Path sample = Path.of("testdata", "stream-sample.mjpeg");
        assumeTrue(Files.exists(sample), "real stream fixture absent; run MockMDS and tools/capture_sbi_fixtures.py");
        byte[] bytes = Files.readAllBytes(sample);
        List<Frame> frames = readAll(new ByteArrayInputStream(bytes), MOCK_TYPE);
        assertTrue(frames.size() >= 60, "expected ~63 frames, got " + frames.size());
        for (Frame f : frames) {
            assertEquals((byte) 0xFF, f.bytes().get(0));
            assertEquals((byte) 0xD8, f.bytes().get(1));
        }
        assertFalse(frames.isEmpty());
    }
}
