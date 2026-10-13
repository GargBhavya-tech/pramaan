/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import pramaan.core.binding.Embeddings;
import pramaan.core.binding.FaceAligner;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;
import pramaan.desktop.ImageIoFrameDecoder;
import pramaan.desktop.IsoFaceDecoder;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;

/**
 * Build Map #56 on real data: the Java alignment and SFace embedding against OpenCV's own
 * (tools/sface_reference.py), and the MockMDS stream vs its JPEG 2000 capture. Skipped without the
 * gitignored model, fixtures and reference.
 */
class RealSFaceTest {

    private static final Path MODELS = Path.of("..", "models");
    private static final Path REF = Path.of("..", "third_party", "parity", "sface");
    private static final Path STREAM = Path.of("..", "sbi", "testdata", "stream-sample.mjpeg");
    private static final Path CAPTURE = Path.of("..", "sbi", "testdata", "rcapture-face.json");

    private static boolean present() {
        return Files.exists(MODELS.resolve(Models.SFACE)) && Files.exists(REF.resolve("reference.json"))
                && Files.exists(STREAM) && Files.exists(CAPTURE) && Models.present(MODELS);
    }

    private static RgbImage firstStreamFrame() throws Exception {
        byte[] data = Files.readAllBytes(STREAM);
        int start = indexOf(data, new byte[] {(byte) 0xFF, (byte) 0xD8}, 0);
        int end = indexOf(data, new byte[] {(byte) 0xFF, (byte) 0xD9}, start) + 2;
        byte[] jpeg = java.util.Arrays.copyOfRange(data, start, end);
        return new ImageIoFrameDecoder().decode(new Frame(jpeg, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                java.time.Instant.EPOCH, 0, null));
    }

    private static int indexOf(byte[] d, byte[] p, int from) {
        outer:
        for (int i = from; i <= d.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (d[i + j] != p[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException("pattern not found");
    }

    private static float[] floats(JsonNode arr) {
        float[] f = new float[arr.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = (float) arr.get(i).asDouble();
        }
        return f;
    }

    private static RgbImage png(Path p) throws Exception {
        BufferedImage img = ImageIO.read(p.toFile());
        byte[] rgb = new byte[img.getWidth() * img.getHeight() * 3];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int i = (y * img.getWidth() + x) * 3;
                rgb[i] = (byte) (argb >> 16);
                rgb[i + 1] = (byte) (argb >> 8);
                rgb[i + 2] = (byte) argb;
            }
        }
        return RgbImage.of(img.getWidth(), img.getHeight(), rgb);
    }

    private static float[] landmarks(FaceBox f) {
        float[] l = new float[10];
        for (int i = 0; i < 5; i++) {
            l[2 * i] = f.landmark(i, 0);
            l[2 * i + 1] = f.landmark(i, 1);
        }
        return l;
    }

    @Test
    void javaMatchesOpenCvAndTheMockStreamMatchesItsCapture() throws Exception {
        assumeTrue(present(), "SFace model, MockMDS fixtures or tools/sface_reference.py output absent");
        JsonNode ref = new ObjectMapper().readTree(REF.resolve("reference.json").toFile());
        try (SFaceEmbedder sface = new SFaceEmbedder(OnnxModel.load(Files.readAllBytes(MODELS.resolve(Models.SFACE))));
                YuNetDetector yunet = Models.detector(MODELS)) {
            // 1. The network alone: OpenCV's aligned crop through our embedder.
            float[] cvStream = floats(ref.get("stream").get("embedding"));
            double network = Embeddings.cosine(sface.embedAligned(png(REF.resolve("aligned_stream.png"))), cvStream);
            assertTrue(network > 0.9999, "same crop, same network: cosine " + network);

            // 2. Our alignment, with OpenCV's landmarks, against OpenCV's crop and embedding.
            RgbImage frame = firstStreamFrame();
            RgbImage ours = FaceAligner.align(frame, floats(ref.get("stream").get("landmarks")));
            RgbImage theirs = png(REF.resolve("aligned_stream.png"));
            double diff = 0;
            for (int y = 0; y < FaceAligner.SIZE; y++) {
                for (int x = 0; x < FaceAligner.SIZE; x++) {
                    for (int c = 0; c < 3; c++) {
                        diff += Math.abs(ours.get(x, y, c) - theirs.get(x, y, c));
                    }
                }
            }
            diff /= FaceAligner.SIZE * FaceAligner.SIZE * 3.0;
            double aligned = Embeddings.cosine(sface.embedAligned(ours), cvStream);
            System.out.printf("SFace parity: crop mean |diff| %.3f levels, embedding cosine %.5f%n", diff, aligned);
            assertTrue(diff < 3, "aligned crop differs from OpenCV's by " + diff + " levels on average");
            assertTrue(aligned > 0.99, "embedding cosine vs OpenCV " + aligned);

            // 3. End to end in Java: YuNet on both images, the capture decoded from its JPEG 2000 ISO record.
            JsonNode cap = new ObjectMapper().readTree(CAPTURE.toFile()).get("biometrics").get(0);
            String payload = cap.get("data").asText().split("\\.")[1];
            JsonNode p = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));
            RgbImage capture = IsoFaceDecoder.decode(Base64.getUrlDecoder().decode(p.get("bioValue").asText().replace("=", "")));
            assertEquals(ref.get("capture_iso").get("width").asInt(), capture.width());
            List<FaceBox> inFrame = yunet.detect(frame);
            List<FaceBox> inCapture = yunet.detect(capture);
            assertEquals(1, inFrame.size());
            assertEquals(1, inCapture.size());
            double sameMock = Embeddings.cosine(sface.embed(frame, landmarks(inFrame.get(0))),
                    sface.embed(capture, landmarks(inCapture.get(0))));
            double cvSame = ref.get("cosine_stream_capture").asDouble();
            System.out.printf("MockMDS stream vs capture: Java %.4f, OpenCV %.4f%n", sameMock, cvSame);
            assertEquals(cvSame, sameMock, 0.03);
            assertTrue(sameMock >= 0.363, "the mock's own stream and capture must bind: " + sameMock);
        }
    }
}
