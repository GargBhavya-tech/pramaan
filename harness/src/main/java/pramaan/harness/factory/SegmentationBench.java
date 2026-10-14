/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;
import pramaan.core.pad.BackdropSubtractionSegmenter;
import pramaan.core.pad.OnnxPersonSegmenter;
import pramaan.core.pad.PersonMask;
import pramaan.core.pad.PersonSegmenter;
import pramaan.core.pad.SceneFingerprint;

/**
 * Scores person segmenters against scenes where the truth is known (Bible Part 43, 45): the rectangle round the
 * resident that the scene cues used before, the no-model {@link BackdropSubtractionSegmenter}, and, if a model file is
 * given, an {@link OnnxPersonSegmenter}. All numbers are SYNTHETIC (docs/claim-sheet.md, L2).
 *
 * <p>Three numbers per scene, because one number hides the trade:
 * <ul>
 *   <li><b>recall</b> - the share of the people's pixels the segmenter caught. Missed person pixels are counted as room
 *       by the scene cues, which is the error this exists to remove;</li>
 *   <li><b>room kept</b> - the share of the real room's pixels the segmenter left alone. Over-masking throws away
 *       background the cues could have used (the old rectangle threw away a lot);</li>
 *   <li><b>IoU</b> - overlap with the truth, for a single summary.</li>
 * </ul>
 *
 * <pre>./gradlew :harness:segmentationBench --args="[--model models/x.onnx --spec u2net320|selfie256] [--overlay dir]"</pre>
 */
public final class SegmentationBench {

    /** A room as fingerprinted, and the same room as the camera sees it later with people in it. */
    public static final class Scenario {
        public final String name;
        public final SyntheticScene empty;
        public final SyntheticScene live;
        public final int[] faceBox;

        Scenario(String name, SyntheticScene empty, SyntheticScene live, int[] faceBox) {
            this.name = name;
            this.empty = empty;
            this.live = live;
            this.faceBox = faceBox;
        }
    }

    /** How well one segmenter did on one scenario. */
    public static final class Score {
        public final double recall;
        public final double roomKept;
        public final double iou;

        Score(double recall, double roomKept, double iou) {
            this.recall = recall;
            this.roomKept = roomKept;
            this.iou = iou;
        }
    }

    private static final int[] CONTRAST = {30, 60, 150};
    private static final int[] WALL = {176, 168, 148};

    private SegmentationBench() {
    }

    /** The scenarios, for one room. Different seeds give different rooms and people. */
    public static List<Scenario> scenarios(long roomSeed) {
        SyntheticScene empty = SyntheticScene.room(roomSeed);
        int[] centreFace = SyntheticScene.faceBoxOf(0.5, 1.0);
        SyntheticScene centre = empty.withPerson(roomSeed + 1, 0.5, 1.0, CONTRAST);
        List<Scenario> out = new ArrayList<>();
        out.add(new Scenario("contrasting shirt, centred", empty, centre, centreFace));
        out.add(new Scenario("shirt the colour of the wall", empty, empty.withPerson(roomSeed + 1, 0.5, 1.0, WALL),
                centreFace));
        out.add(new Scenario("person off to the left", empty, empty.withPerson(roomSeed + 2, 0.22, 1.0, CONTRAST),
                SyntheticScene.faceBoxOf(0.22, 1.0)));
        out.add(new Scenario("far away (smaller)", empty, empty.withPerson(roomSeed + 3, 0.6, 0.6, CONTRAST),
                SyntheticScene.faceBoxOf(0.6, 0.6)));
        out.add(new Scenario("someone walking behind", empty,
                empty.withPerson(roomSeed + 4, 0.35, 1.0, CONTRAST).withPerson(roomSeed + 5, 0.84, 0.55,
                        new int[] {120, 40, 40}), SyntheticScene.faceBoxOf(0.35, 1.0)));
        out.add(new Scenario("15% brighter than at set-up", empty, centre.lit(1.15), centreFace));
        out.add(new Scenario("25% darker than at set-up", empty, centre.lit(0.75), centreFace));
        out.add(new Scenario("a poster moved since set-up", empty, centre.rearranged(roomSeed + 6), centreFace));
        return out;
    }

    /** What the scene cues used before: the face grown to a body, as {@code SceneContext} did. */
    public static PersonMask rectangle(RgbImage frame, int[] faceBox) {
        int w = faceBox[2] - faceBox[0];
        int h = faceBox[3] - faceBox[1];
        int[] box = {Math.max(0, Math.round(faceBox[0] - 0.6f * w)), Math.max(0, Math.round(faceBox[1] - 0.4f * h)),
            Math.min(frame.width(), Math.round(faceBox[0] + 1.6f * w)), frame.height()};
        return PersonMask.ofBox(frame.width(), frame.height(), box);
    }

    public static Score score(PersonMask found, PersonMask truth) {
        int people = 0;
        int caught = 0;
        int room = 0;
        int roomKept = 0;
        for (int y = 0; y < PersonMask.H; y++) {
            for (int x = 0; x < PersonMask.W; x++) {
                if (truth.person(x, y)) {
                    people++;
                    caught += found.person(x, y) ? 1 : 0;
                } else {
                    room++;
                    roomKept += found.person(x, y) ? 0 : 1;
                }
            }
        }
        return new Score(people == 0 ? 1 : (double) caught / people, room == 0 ? 1 : (double) roomKept / room,
                found.iou(truth));
    }

    /** The no-model route. */
    public static PersonSegmenter subtraction() {
        return new BackdropSubtractionSegmenter();
    }

    /** @param method "rectangle" for the old stand-in; anything else uses {@code segmenter} */
    public static Score run(Scenario s, String method, PersonSegmenter segmenter) {
        return score(found(s, method, segmenter), s.live.truth());
    }

    private static PersonMask found(Scenario s, String method, PersonSegmenter segmenter) {
        RgbImage frame = s.live.image();
        return method.equals("rectangle") ? rectangle(frame, s.faceBox)
                : segmenter.segment(frame, s.faceBox, SceneFingerprint.capture(s.empty.image()));
    }

    // ------------------------------------------------------------------------------------------ the command

    public static void main(String[] args) throws Exception {
        Path model = null;
        String spec = "u2net320";
        Path overlay = null;
        Path photo = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--photo":
                    photo = Path.of(args[i + 1]);
                    break;
                case "--model":
                    model = Path.of(args[i + 1]);
                    break;
                case "--spec":
                    spec = args[i + 1];
                    break;
                case "--overlay":
                    overlay = Path.of(args[i + 1]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Map<String, PersonSegmenter> methods = new LinkedHashMap<>();
        methods.put("rectangle", null);
        methods.put("subtraction", subtraction());
        if (model != null) {
            OnnxPersonSegmenter.Spec s = spec.equals("selfie256") ? OnnxPersonSegmenter.Spec.selfie256()
                    : OnnxPersonSegmenter.Spec.u2net320();
            methods.put("model", OnnxPersonSegmenter.of(OnnxModel.load(Files.readAllBytes(model)), s));
        }
        if (photo != null) {
            realPhoto(photo, methods, overlay == null ? Path.of("build", "segmentation") : overlay);
            return;
        }
        timing(methods);
        System.out.println("SYNTHETIC scenes, held-out rooms. recall = person pixels caught; room kept = room pixels"
                + " left usable; IoU = overlap.");
        for (long seed : new long[] {101, 202, 303}) {
            System.out.println("\nroom seed " + seed);
            System.out.printf("%-32s", "scenario");
            for (String m : methods.keySet()) {
                System.out.printf(" | %-20s", m + " rec/kept/IoU");
            }
            System.out.println();
            for (Scenario s : scenarios(seed)) {
                System.out.printf("%-32s", s.name);
                for (Map.Entry<String, PersonSegmenter> m : methods.entrySet()) {
                    Score sc = run(s, m.getKey(), m.getValue());
                    System.out.printf(" | %5.2f %5.2f %5.2f   ", sc.recall, sc.roomKept, sc.iou);
                    if (overlay != null && seed == 101) {
                        writeOverlay(overlay, s, m.getKey(), found(s, m.getKey(), m.getValue()));
                    }
                }
                System.out.println();
            }
        }
    }

    /** Milliseconds per call on a 640 x 480 frame, after a warm-up, so the routes' cost can be compared. */
    private static void timing(Map<String, PersonSegmenter> methods) {
        Scenario s = scenarios(101).get(0);
        RgbImage frame = s.live.image();
        SceneFingerprint stored = SceneFingerprint.capture(s.empty.image());
        for (Map.Entry<String, PersonSegmenter> m : methods.entrySet()) {
            if (m.getValue() == null) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                m.getValue().segment(frame, s.faceBox, stored);
            }
            long t = System.nanoTime();
            int n = 20;
            for (int i = 0; i < n; i++) {
                m.getValue().segment(frame, s.faceBox, stored);
            }
            System.out.printf("%-12s %.1f ms per frame%n", m.getKey(), (System.nanoTime() - t) / 1e6 / n);
        }
        System.out.println();
    }

    /**
     * What each route that can run without a stored room does to one real photograph. There is no truth to score
     * against, so this only writes overlays to look at. The face box is a guess at the middle of the frame.
     */
    private static void realPhoto(Path photo, Map<String, PersonSegmenter> methods, Path dir) throws IOException {
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
        RgbImage frame = RgbImage.of(in.getWidth(), in.getHeight(), px);
        int[] face = {frame.width() * 38 / 100, frame.height() * 12 / 100, frame.width() * 62 / 100,
            frame.height() * 45 / 100};
        Files.createDirectories(dir);
        for (Map.Entry<String, PersonSegmenter> m : methods.entrySet()) {
            if (m.getKey().equals("model")) {
                PersonMask found = m.getValue().segment(frame, face, null);
                BufferedImage out = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < frame.height(); y++) {
                    for (int x = 0; x < frame.width(); x++) {
                        int r = frame.get(x, y, 0);
                        int g = frame.get(x, y, 1);
                        int b = frame.get(x, y, 2);
                        if (found.personAtFrame(x, y, frame.width(), frame.height())) {
                            r = (r + 255) / 2;
                            g /= 2;
                            b /= 2;
                        }
                        out.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
                ImageIO.write(out, "png", dir.resolve("photo-model.png").toFile());
                System.out.printf("model: %.0f%% of the photograph is a person%n", 100 * found.personFraction());
            }
        }
    }

    private static void writeOverlay(Path dir, Scenario s, String method, PersonMask found) throws IOException {
        Files.createDirectories(dir);
        RgbImage frame = s.live.image();
        BufferedImage img = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int r = frame.get(x, y, 0);
                int g = frame.get(x, y, 1);
                int b = frame.get(x, y, 2);
                if (found.personAtFrame(x, y, frame.width(), frame.height())) {
                    r = (r + 255) / 2; // tinted red where the segmenter says person
                    g /= 2;
                    b /= 2;
                }
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", dir.resolve(s.name.replaceAll("[^a-z0-9]+", "-") + "-" + method + ".png").toFile());
    }
}
