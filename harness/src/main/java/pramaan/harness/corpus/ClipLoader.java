/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import pramaan.core.source.Frame;
import pramaan.sbi.MjpegStreamReader;

/** Loads a clip as encoded frames: a directory of JPEG files, or an M-JPEG capture such as stream-sample.mjpeg. */
public final class ClipLoader {

    private ClipLoader() {
    }

    public static List<byte[]> load(Path clip) throws IOException {
        List<byte[]> frames = Files.isDirectory(clip) ? fromDirectory(clip) : fromMjpeg(clip);
        if (frames.isEmpty()) {
            throw new IOException("clip has no frames: " + clip);
        }
        return frames;
    }

    static List<byte[]> fromDirectory(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".jpg") || n.endsWith(".jpeg");
            }).sorted().collect(Collectors.toList());
        }
        List<byte[]> frames = new ArrayList<>();
        for (Path f : files) {
            frames.add(Files.readAllBytes(f));
        }
        return frames;
    }

    /** Reads a multipart M-JPEG file; the delimiter is taken from its first line. */
    static List<byte[]> fromMjpeg(Path file) throws IOException {
        String delimiter;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(256);
            String text = new String(head, StandardCharsets.ISO_8859_1);
            int eol = text.indexOf('\n');
            delimiter = (eol < 0 ? text : text.substring(0, eol)).trim();
        }
        if (!delimiter.startsWith("--")) {
            throw new IOException("not an M-JPEG multipart file (no leading delimiter): " + file);
        }
        List<byte[]> frames = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            MjpegStreamReader reader = new MjpegStreamReader(in, "multipart/x-mixed-replace; boundary=" + delimiter,
                    Clock.systemUTC(), null);
            Frame f;
            while ((f = reader.next()) != null) {
                frames.add(f.copyBytes());
            }
        }
        return frames;
    }
}
