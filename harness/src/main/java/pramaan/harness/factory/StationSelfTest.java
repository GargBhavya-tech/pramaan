/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.MoireCue;
import pramaan.core.pad.SceneConsistencyCue;
import pramaan.core.pad.SceneFingerprint;

/**
 * An install-time self-test (Bible Part 43, 45): given the photograph of this station's empty room (the one the backdrop
 * was fingerprinted from), run a set of controls and attacks <em>made from that very room</em> through the real scene
 * and moire cues, and say which the station would call live. It is the station asking "what would fool me, here?"
 * before a resident or an attacker does.
 *
 * <p>The scenarios: the room again (must be live); the room brighter and darker and with furniture moved (must stay live, or honest residents are
 * rejected by the afternoon sun); a different room (must not be live); and a screen replay of this
 * room at several display pitches, which is the attack the scene cue cannot see by construction (it is the right room),
 * so the table shows which replays the moire cue then catches.
 *
 * <p>SYNTHETIC attacks on one photograph: the result says how the cues behave in this room's colours and corners, not how
 * a real phone or a real replay behaves, and it is not a certification.
 *
 * <pre>./gradlew :harness:stationSelfTest --args="--photo empty-room.jpg"</pre>
 */
public final class StationSelfTest {

    /** One scenario's outcome. */
    public static final class Row {
        public final String name;
        /** True for a scenario that must be called live (a control); false for an attack. */
        public final boolean shouldBeLive;
        public final double scenePLive;
        public final double moirePLive;
        /** A control the cues rejected, or an attack both cues called live. */
        public final boolean wrong;

        Row(String name, boolean shouldBeLive, double scenePLive, double moirePLive, boolean wrong) {
            this.name = name;
            this.shouldBeLive = shouldBeLive;
            this.scenePLive = scenePLive;
            this.moirePLive = moirePLive;
            this.wrong = wrong;
        }
    }

    /** The resident in the frame: a smooth, lightly textured block where a face would be, as the moire cue expects. */
    private static final int[] FACE_BOX = {75, 35, 245, 205};
    private static final RgbImage FACE = RedTeamFuzz.face(170);
    private static final int SIZE_W = 320;
    private static final int SIZE_H = 240;

    private StationSelfTest() {
    }

    /** @param room the station's empty-room photograph, any size */
    public static List<Row> run(RgbImage room) {
        RgbImage base = ImageOps.resize(room, SIZE_W, SIZE_H);
        SceneFingerprint stored = SceneFingerprint.capture(base);
        List<Row> rows = new ArrayList<>();
        rows.add(row("the room again (control)", true, stored, withFace(base)));
        rows.add(row("15% brighter (control)", true, stored, withFace(scale(base, 1.15))));
        rows.add(row("25% darker (control)", true, stored, withFace(scale(base, 0.75))));
        rows.add(row("furniture moved, same room (control)", true, stored, withFace(patched(base))));
        rows.add(row("a different room", false, stored,
                withFace(ImageOps.resize(SyntheticScene.room(99).image(), SIZE_W, SIZE_H))));
        for (double pitch : new double[] {0.8, 1.0, 1.15, 1.4, 1.8}) {
            ScreenRecapture.Params p = new ScreenRecapture.Params();
            p.displayPitchPx = pitch;
            p.leak = 0;
            p.gap = 0.15;
            p.glare = 0;
            p.ripple = 0;
            p.supersample = 3;
            RgbImage replay = ScreenRecapture.render(withFace(base), p);
            rows.add(row(String.format("screen replay of this room, pitch %.2f", pitch), false, stored, replay));
        }
        return rows;
    }

    private static Row row(String name, boolean shouldBeLive, SceneFingerprint stored, RgbImage frame) {
        CueResult scene = new SceneConsistencyCue().evaluate(stored, ImageOps.resize(frame, SIZE_W, SIZE_H), FACE_BOX);
        CueResult moire = moire(frame);
        double sp = scene.abstained() ? Double.NaN : scene.pLive();
        double mp = moire.abstained() ? Double.NaN : moire.pLive();
        boolean sceneLive = !Double.isNaN(sp) && sp > 0.5;
        boolean moireLive = Double.isNaN(mp) || mp > 0.5; // a cue that abstains did not catch it
        boolean wrong = shouldBeLive ? !(sceneLive && moireLive) : (sceneLive && moireLive);
        return new Row(name, shouldBeLive, sp, mp, wrong);
    }

    private static CueResult moire(RgbImage frame) {
        int w = frame.width();
        int h = frame.height();
        if (w < 128 || h < 128) {
            return CueResult.abstain(MoireCue.NAME, CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        return new MoireCue().evaluate(ImageOps.crop(frame, w / 2 - 64, h / 2 - 64, w / 2 + 63, h / 2 + 63));
    }

    /** The frame with the stand-in face pasted where the resident stands. */
    private static RgbImage withFace(RgbImage img) {
        byte[] b = new byte[img.width() * img.height() * 3];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                boolean inside = x >= FACE_BOX[0] && x < FACE_BOX[2] && y >= FACE_BOX[1] && y < FACE_BOX[3];
                for (int c = 0; c < 3; c++) {
                    b[(y * img.width() + x) * 3 + c] = (byte) (inside ? FACE.get(x - FACE_BOX[0], y - FACE_BOX[1], c)
                            : img.get(x, y, c));
                }
            }
        }
        return RgbImage.of(img.width(), img.height(), b);
    }

    private static RgbImage scale(RgbImage img, double gain) {
        byte[] b = new byte[img.width() * img.height() * 3];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                for (int c = 0; c < 3; c++) {
                    b[(y * img.width() + x) * 3 + c] = (byte) Math.max(0, Math.min(255, Math.round(img.get(x, y, c) * gain)));
                }
            }
        }
        return RgbImage.of(img.width(), img.height(), b);
    }

    /** A fifth of the frame repainted, as if furniture had been moved. */
    private static RgbImage patched(RgbImage img) {
        byte[] b = new byte[img.width() * img.height() * 3];
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                boolean inside = x > img.width() * 0.1 && x < img.width() * 0.55 && y > img.height() * 0.1
                        && y < img.height() * 0.55;
                for (int c = 0; c < 3; c++) {
                    b[(y * img.width() + x) * 3 + c] = inside ? (byte) (c == 0 ? 200 : 40) : (byte) img.get(x, y, c);
                }
            }
        }
        return RgbImage.of(img.width(), img.height(), b);
    }

    public static void main(String[] args) throws IOException {
        Path photo = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--photo")) {
                photo = Path.of(args[i + 1]);
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        RgbImage room = photo == null ? SyntheticScene.room(7).image() : read(photo);
        System.out.println((photo == null ? "SYNTHETIC room (no --photo given)" : "room photo " + photo)
                + ", SYNTHETIC attacks; scene and moire cues, provisional thresholds");
        System.out.printf("%-42s %-9s %-7s %-7s %s%n", "scenario", "expected", "scene", "moire", "");
        int wrong = 0;
        for (Row r : run(room)) {
            System.out.printf("%-42s %-9s %-7s %-7s %s%n", r.name, r.shouldBeLive ? "live" : "not live", fmt(r.scenePLive),
                    fmt(r.moirePLive), r.wrong ? (r.shouldBeLive ? "<-- honest case rejected" : "<-- FOOLED") : "");
            wrong += r.wrong ? 1 : 0;
        }
        System.out.println(wrong + " of the scenarios went the wrong way. Not a certification.");
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.2f", v);
    }

    private static RgbImage read(Path photo) throws IOException {
        java.awt.image.BufferedImage in = ImageIO.read(photo.toFile());
        if (in == null) {
            throw new IOException("not an image: " + photo);
        }
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
        return RgbImage.of(in.getWidth(), in.getHeight(), px);
    }
}
