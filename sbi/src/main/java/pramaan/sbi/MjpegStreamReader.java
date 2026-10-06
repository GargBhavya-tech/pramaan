/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;

/**
 * Incremental reader for {@code multipart/x-mixed-replace} M-JPEG streams (Build Map #13).
 *
 * <p>Reads one part at a time — never the whole body, which never ends. Accepts both the RFC 2046
 * delimiter ({@code "--" + boundary}) and MOSIP MockMDS's variant, which declares
 * {@code boundary=--BoundaryString} and then writes {@code --BoundaryString} as the delimiter.
 * A truncated final part ends the stream quietly ({@link #truncated()}), rather than failing it.
 */
public final class MjpegStreamReader {

    /** A part larger than this is treated as a broken stream, not an image. */
    public static final int MAX_PART_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 4096;
    private static final byte CR = 13;
    private static final byte LF = 10;

    private final InputStream in;
    private final String rfcDelimiter;
    private final String literalDelimiter;
    private final Clock clock;
    private final DeviceMetadata device;
    private long index;
    private boolean truncated;
    private boolean finished;

    public MjpegStreamReader(InputStream in, String contentType, Clock clock, DeviceMetadata device) {
        this.in = new BufferedInputStream(Objects.requireNonNull(in, "in"), 64 * 1024);
        String boundary = boundaryOf(Objects.requireNonNull(contentType, "contentType"));
        this.rfcDelimiter = "--" + boundary;
        this.literalDelimiter = boundary;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.device = device;
    }

    static String boundaryOf(String contentType) {
        for (String param : contentType.split(";")) {
            String p = param.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                if (!b.isEmpty()) {
                    return b;
                }
            }
        }
        throw new IllegalArgumentException("no multipart boundary in content type: " + contentType);
    }

    /** The next frame, or null at the end of the stream (clean or truncated). */
    public Frame next() throws IOException {
        if (finished) {
            return null;
        }
        String line;
        do {
            line = readLine();
            if (line == null) {
                finished = true;
                return null;
            }
        } while (line.isEmpty());
        if (isEndDelimiter(line)) {
            finished = true;
            return null;
        }
        if (!isDelimiter(line)) {
            throw new IOException("expected a multipart delimiter, found: " + abbreviate(line));
        }

        int contentLength = -1;
        String partType = "";
        while (true) {
            String header = readLine();
            if (header == null) {
                truncated = true;
                finished = true;
                return null;
            }
            if (header.isEmpty()) {
                break;
            }
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length: " + abbreviate(value), e);
                }
                if (contentLength < 0 || contentLength > MAX_PART_BYTES) {
                    throw new IOException("part size " + contentLength + " outside 0.." + MAX_PART_BYTES);
                }
            } else if (name.equals("content-type")) {
                partType = value.toLowerCase(Locale.ROOT);
            }
        }

        byte[] body = contentLength >= 0 ? readExactly(contentLength) : readUntilDelimiter();
        if (body == null) {
            truncated = true;
            finished = true;
            return null;
        }
        if (!partType.isEmpty() && !partType.startsWith("image/jpeg")) {
            throw new IOException("unexpected part type '" + partType + "' in an M-JPEG stream");
        }
        return new Frame(body, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, clock.instant(), index++, device);
    }

    /** True if the stream ended in the middle of a part. */
    public boolean truncated() {
        return truncated;
    }

    public void close() throws IOException {
        in.close();
    }

    private boolean isDelimiter(String line) {
        return line.equals(rfcDelimiter) || line.equals(literalDelimiter);
    }

    private boolean isEndDelimiter(String line) {
        return line.equals(rfcDelimiter + "--") || line.equals(literalDelimiter + "--");
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] out = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(out, read, n - read);
            if (r < 0) {
                return null;
            }
            read += r;
        }
        return out;
    }

    /** No Content-Length: read until a line holding the delimiter, then leave it to be parsed next. */
    private byte[] readUntilDelimiter() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] rfc = ("\r\n" + rfcDelimiter).getBytes(StandardCharsets.US_ASCII);
        byte[] literal = ("\r\n" + literalDelimiter).getBytes(StandardCharsets.US_ASCII);
        while (true) {
            in.mark(rfc.length + 1);
            if (startsWith(rfc) || startsWith(literal)) {
                in.reset();
                in.skip(2);
                return trimTrailingLineBreaks(body.toByteArray());
            }
            in.reset();
            int b = in.read();
            if (b < 0) {
                return null;
            }
            body.write(b);
            if (body.size() > MAX_PART_BYTES) {
                throw new IOException("part exceeds " + MAX_PART_BYTES + " bytes without a delimiter");
            }
        }
    }

    /** Separator CR/LF bytes before the next delimiter are not part of the image (a JPEG ends in FF D9). */
    private static byte[] trimTrailingLineBreaks(byte[] body) {
        int end = body.length;
        while (end > 0 && (body[end - 1] == CR || body[end - 1] == LF)) {
            end--;
        }
        return end == body.length ? body : java.util.Arrays.copyOf(body, end);
    }

    private boolean startsWith(byte[] prefix) throws IOException {
        in.mark(prefix.length + 1);
        for (byte expected : prefix) {
            int b = in.read();
            if (b != (expected & 0xFF)) {
                in.reset();
                return false;
            }
        }
        in.reset();
        return true;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (b == '\n') {
                break;
            }
            line.write(b);
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("header line longer than " + MAX_LINE_BYTES + " bytes");
            }
        }
        String s = line.toString(StandardCharsets.ISO_8859_1.name());
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
