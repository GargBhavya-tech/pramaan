/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.SceneConsistencyCue;
import pramaan.core.pad.SceneFingerprint;

/**
 * Installation step for the scene and illumination cues (Bible Part 43; Build Map #87): fingerprint the <em>empty</em>
 * backdrop of this station from one photograph of it, and write the fingerprint where the scaffold's
 * {@code --backdrop} option reads it.
 *
 * <pre>./gradlew :desktop:captureBackdrop --args="--image empty-room.jpg --out build/scaffold/backdrop.bin"</pre>
 *
 * <p>Photograph the room with nobody in it, from the registration camera, in the lighting it is normally used in.
 * Do this again, and have it logged, after the camera is moved or the backdrop is changed — the engine reports a
 * bumped camera in the audit log as {@code BACKDROP_CAMERA_MOVED}. The fingerprint holds colour statistics and
 * corner descriptors, not a picture, and refuses a blank wall: too few corners to tell this room from another.
 */
public final class BackdropTool {

    private BackdropTool() {
    }

    public static void main(String[] args) throws IOException {
        Path image = null;
        Path out = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--image":
                    image = Path.of(args[i + 1]);
                    break;
                case "--out":
                    out = Path.of(args[i + 1]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (image == null || out == null) {
            System.err.println("usage: --image <photo of the empty room> --out <fingerprint file>");
            System.exit(2);
        }
        SceneFingerprint fp = fingerprint(image);
        if (fp.keypointCount() < SceneConsistencyCue.MIN_FINGERPRINT_KEYPOINTS) {
            System.err.println("only " + fp.keypointCount() + " corners found: this backdrop is too plain to tell from"
                    + " another room. Photograph a part of the room with some structure in it.");
            System.exit(1);
        }
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        Files.write(out, fp.toBytes());
        System.out.println("wrote " + out + " (" + fp.keypointCount() + " corners)");
    }

    /** The fingerprint of the room in a photograph. */
    static SceneFingerprint fingerprint(Path image) throws IOException {
        return fingerprint(Files.readAllBytes(image));
    }

    /** The fingerprint of the room in an encoded photograph (JPEG, PNG, ...). */
    static SceneFingerprint fingerprint(byte[] photo) throws IOException {
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(photo));
        if (img == null) {
            throw new IOException("not an image");
        }
        byte[] rgb = new byte[img.getWidth() * img.getHeight() * 3];
        int k = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                rgb[k++] = (byte) (p >> 16);
                rgb[k++] = (byte) (p >> 8);
                rgb[k++] = (byte) p;
            }
        }
        return SceneFingerprint.capture(RgbImage.of(img.getWidth(), img.getHeight(), rgb));
    }
}
