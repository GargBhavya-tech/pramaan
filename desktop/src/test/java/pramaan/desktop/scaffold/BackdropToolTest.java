/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.pad.SceneConsistencyCue;
import pramaan.core.pad.SceneFingerprint;

/** Build Map #87: the installation tool that fingerprints an empty room, and the scaffold options that use it. */
class BackdropToolTest {

    private static Path photo(Path dir, String name, boolean structured) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 120, 120));
        g.fillRect(0, 0, 320, 240);
        if (structured) {
            Random r = new Random(5);
            for (int i = 0; i < 70; i++) {
                g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
                g.fillRect(r.nextInt(280), r.nextInt(200), 10 + r.nextInt(40), 10 + r.nextInt(40));
            }
        }
        g.dispose();
        Path out = dir.resolve(name);
        ImageIO.write(img, "png", out.toFile());
        return out;
    }

    @Test
    void aRoomWithStructureGivesAUsableFingerprintThatSurvivesTheFile(@TempDir Path dir) throws IOException {
        SceneFingerprint fp = BackdropTool.fingerprint(photo(dir, "room.png", true));
        assertTrue(fp.keypointCount() >= SceneConsistencyCue.MIN_FINGERPRINT_KEYPOINTS, "corners " + fp.keypointCount());
        Path file = dir.resolve("backdrop.bin");
        Files.write(file, fp.toBytes());
        assertEquals(fp.keypointCount(), SceneFingerprint.fromBytes(Files.readAllBytes(file)).keypointCount());
    }

    @Test
    void aBlankWallHasTooFewCornersToBeAFingerprint(@TempDir Path dir) throws IOException {
        SceneFingerprint fp = BackdropTool.fingerprint(photo(dir, "wall.png", false));
        assertTrue(fp.keypointCount() < SceneConsistencyCue.MIN_FINGERPRINT_KEYPOINTS, "corners " + fp.keypointCount());
    }

    @Test
    void somethingThatIsNotAnImageIsAnError(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("junk.png");
        Files.write(junk, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> BackdropTool.fingerprint(junk));
    }

    @Test
    void theScaffoldTakesTheResidentMonitorAndTheBackdropFromItsOptions() {
        ScaffoldConfig c = ScaffoldConfig.parse(List.of("--flash-screen", "1", "--backdrop", "build/scaffold/backdrop.bin"));
        assertEquals(1, c.flashScreen);
        assertEquals(Path.of("build/scaffold/backdrop.bin"), c.backdrop);
    }

    @Test
    void withoutThoseOptionsNeitherIsConfigured() {
        ScaffoldConfig c = ScaffoldConfig.parse(List.of());
        assertEquals(-1, c.flashScreen, "no resident light unless one is named");
        assertEquals(null, c.backdrop);
    }
}
