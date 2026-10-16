/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

/** Bible Part 5: a camera writes the same JPEG headers every frame; a pipeline that re-encodes writes its own. */
class JpegSignatureTest {

    /** A JPEG of random content from this JDK's encoder at the given quality, optionally progressive. */
    static byte[] jpeg(long seed, float quality, boolean progressive, int width) throws IOException {
        BufferedImage img = new BufferedImage(width, 96, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seed);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, r.nextInt(0xFFFFFF));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        param.setProgressiveMode(progressive ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        }
        writer.dispose();
        return out.toByteArray();
    }

    @Test
    void sameEncoderSameSettingsGiveTheSameSignatureWhateverThePicture() throws Exception {
        JpegSignature a = JpegSignature.of(jpeg(1, 0.8f, false, 128));
        JpegSignature b = JpegSignature.of(jpeg(2, 0.8f, false, 128));
        assertNotNull(a);
        assertEquals(a.hex(), b.hex());
    }

    @Test
    void aDifferentImageSizeFromTheSameCameraStillMatches() throws Exception {
        assertEquals(JpegSignature.of(jpeg(1, 0.8f, false, 128)).hex(), JpegSignature.of(jpeg(1, 0.8f, false, 160)).hex());
    }

    @Test
    void anotherQualitySettingIsAnotherEncoder() throws Exception {
        JpegSignature a = JpegSignature.of(jpeg(1, 0.8f, false, 128));
        JpegSignature b = JpegSignature.of(jpeg(1, 0.6f, false, 128));
        assertNotEquals(a.hex(), b.hex());
        assertTrue(a.summary().contains("q~"), a.summary());
    }

    @Test
    void progressiveAndBaselineAreDifferent() throws Exception {
        JpegSignature base = JpegSignature.of(jpeg(1, 0.8f, false, 128));
        JpegSignature prog = JpegSignature.of(jpeg(1, 0.8f, true, 128));
        assertNotEquals(base.hex(), prog.hex());
        assertTrue(base.summary().startsWith("baseline"), base.summary());
        assertTrue(prog.summary().startsWith("progressive"), prog.summary());
    }

    @Test
    void theSummaryIsReadable() throws Exception {
        String s = JpegSignature.of(jpeg(1, 0.8f, false, 128)).summary();
        assertTrue(s.contains("DHT"), s);
    }

    @Test
    void thingsThatAreNotJpegHeadersGiveNull() {
        assertNull(JpegSignature.of(null));
        assertNull(JpegSignature.of(new byte[] {1, 2, 3, 4, 5}));
        assertNull(JpegSignature.of(new byte[] {(byte) 0xFF, (byte) 0xD8, 9, 8, 7}));
        assertNull(JpegSignature.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB, 0, 80, 1, 2}));
    }

    @Test
    void aTruncatedJpegIsRefusedNotCrashedOn() throws Exception {
        byte[] full = jpeg(1, 0.8f, false, 128);
        for (int cut : new int[] {10, 40, 120, 300}) {
            JpegSignature s = JpegSignature.of(java.util.Arrays.copyOf(full, cut));
            // either unreadable, or cut after the headers: never an exception
            if (s != null) {
                assertEquals(JpegSignature.of(full).hex(), s.hex());
            }
        }
    }

    /**
     * The parser reads bytes a hostile virtual camera chooses. Whatever it is fed, it must answer with a signature or
     * null and never throw, loop or allocate without bound: 20 000 mutated, truncated and spliced JPEGs.
     */
    @Test
    void noInputEverMakesTheParserThrowOrHang() throws Exception {
        byte[][] seeds = {jpeg(1, 0.8f, false, 128), jpeg(2, 0.3f, true, 96), jpeg(3, 0.95f, false, 64)};
        Random r = new Random(99);
        long start = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            byte[] b = seeds[r.nextInt(seeds.length)].clone();
            switch (r.nextInt(4)) {
                case 0: // flip a few bytes anywhere, including lengths
                    for (int k = 0; k < 1 + r.nextInt(6); k++) {
                        b[r.nextInt(b.length)] = (byte) r.nextInt(256);
                    }
                    break;
                case 1: // truncate
                    b = java.util.Arrays.copyOf(b, r.nextInt(b.length));
                    break;
                case 2: // flip bytes in the header region only, where the parser lives
                    for (int k = 0; k < 1 + r.nextInt(4); k++) {
                        b[r.nextInt(Math.min(b.length, 700))] = (byte) r.nextInt(256);
                    }
                    break;
                default: // random bytes behind a valid start-of-image
                    b = new byte[2 + r.nextInt(300)];
                    r.nextBytes(b);
                    b[0] = (byte) 0xFF;
                    b[1] = (byte) 0xD8;
            }
            JpegSignature.of(b); // must not throw
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 20_000, "20 000 parses took too long: a hang or a blow-up");
    }

    // ------------------------------------------------------------------------------------------ the profile

    @Test
    void aProfileEnrolsTheCamerasSignatureAndSurvivesItsFile() throws Exception {
        StreamSignatureProfile p = StreamSignatureProfile.enrol("Acme Cam1", Instant.parse("2026-10-14T10:00:00Z"),
                List.of(jpeg(1, 0.8f, false, 128), jpeg(2, 0.8f, false, 128)));
        assertEquals(1, p.signatures().size());
        StreamSignatureProfile back = StreamSignatureProfile.fromJson(p.toJson());
        assertEquals(p.signatures(), back.signatures());
        assertEquals("Acme Cam1", back.device());
        assertEquals(Instant.parse("2026-10-14T10:00:00Z"), back.enrolledAt());
        assertTrue(back.contains(JpegSignature.of(jpeg(9, 0.8f, false, 99))));
        assertFalse(back.contains(JpegSignature.of(jpeg(9, 0.5f, false, 99))));
    }

    @Test
    void enrolmentRefusesFramesThatAreNotJpegAndCamerasWithNoStableHeaders() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> StreamSignatureProfile.enrol("x", Instant.now(), List.of(new byte[] {1, 2, 3})));
        java.util.List<byte[]> many = new java.util.ArrayList<>();
        for (int q = 10; q <= 90; q += 8) {
            many.add(jpeg(1, q / 100f, false, 64));
        }
        assertThrows(IllegalArgumentException.class, () -> StreamSignatureProfile.enrol("x", Instant.now(), many));
    }

    @Test
    void aProfileFileThatIsNotOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StreamSignatureProfile.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> StreamSignatureProfile.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> StreamSignatureProfile.fromJson(
                "{\"version\":1,\"device\":\"d\",\"enrolledAt\":\"2026-10-14T10:00:00Z\",\"signatures\":[{\"sha256\":\"zz\"}]}"));
    }

    // ------------------------------------------------------------------------------------------ the watch

    private static final DeviceMetadata DEVICE = new DeviceMetadata("d", "0", "c", DeviceLevel.L1, "Acme", "Cam1", "1");

    private static Frame frame(byte[] bytes, int index) {
        return new Frame(bytes, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, Instant.ofEpochMilli(index * 100L), index,
                DEVICE);
    }

    @Test
    void framesFromTheEnrolledEncoderAreNeverMismatches() throws Exception {
        StreamSignatureWatch w = new StreamSignatureWatch(StreamSignatureProfile.enrol("c", Instant.now(),
                List.of(jpeg(1, 0.8f, false, 128))));
        for (int i = 0; i < 20; i++) {
            w.observe(frame(jpeg(10 + i, 0.8f, false, 128), i));
        }
        assertEquals(20, w.frames());
        assertEquals(0, w.mismatching());
        assertFalse(w.foreignEncoder());
    }

    @Test
    void aReEncodingPipelineIsSeenAfterThreeFramesAndNotBeforeAndAOneOffIsForgiven() throws Exception {
        StreamSignatureWatch w = new StreamSignatureWatch(StreamSignatureProfile.enrol("c", Instant.now(),
                List.of(jpeg(1, 0.8f, false, 128))));
        w.observe(frame(jpeg(2, 0.5f, false, 128), 0));
        w.observe(frame(jpeg(3, 0.5f, false, 128), 1));
        assertFalse(w.foreignEncoder(), "two frames are a glitch");
        w.observe(frame(jpeg(4, 0.5f, false, 128), 2));
        assertTrue(w.foreignEncoder());
        assertEquals(1, w.unexpected().size());
    }

    @Test
    void withNothingEnrolledNothingIsJudged() throws Exception {
        StreamSignatureWatch w = new StreamSignatureWatch(null);
        for (int i = 0; i < 10; i++) {
            w.observe(frame(jpeg(i, 0.3f, false, 128), i));
        }
        assertFalse(w.enrolled());
        assertEquals(10, w.frames());
        assertEquals(0, w.mismatching());
        assertFalse(w.foreignEncoder());
    }

    @Test
    void framesThatAreNotJpegAreCountedAsUnreadableNotAsAnotherEncoder() throws Exception {
        StreamSignatureWatch w = new StreamSignatureWatch(StreamSignatureProfile.enrol("c", Instant.now(),
                List.of(jpeg(1, 0.8f, false, 128))));
        for (int i = 0; i < 5; i++) {
            w.observe(frame(new byte[] {(byte) 0xFF, (byte) 0xD8, 9, 8, 7}, i));
        }
        assertEquals(5, w.unreadable());
        assertEquals(0, w.mismatching());
    }
}
