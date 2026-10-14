/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.audit.AuditEvent;
import pramaan.core.pad.SceneFingerprint;
import pramaan.core.testing.TestClock;

/** Build Map #87: replacing the stored backdrop is an audited, reversible act that never half-succeeds. */
class BackdropRetakeTest {

    private final TestClock clock = TestClock.at("2026-10-14T10:00:00Z");
    private final List<AuditEvent> audits = new ArrayList<>();

    private static byte[] photo(long seed, boolean structured) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 120, 120));
        g.fillRect(0, 0, 320, 240);
        if (structured) {
            Random r = new Random(seed);
            for (int i = 0; i < 70; i++) {
                g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
                g.fillRect(r.nextInt(280), r.nextInt(200), 10 + r.nextInt(40), 10 + r.nextInt(40));
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private BackdropRetake.Result retake(byte[] photo, Path target) throws IOException {
        return BackdropRetake.retake(photo, target, "admin-1", "camera was moved", true, clock, audits::add);
    }

    @Test
    void aStructuredRoomIsSavedAndTheFileReadsBack(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("backdrop.bin");
        BackdropRetake.Result r = retake(photo(5, true), target);
        assertEquals(BackdropRetake.Outcome.SAVED, r.outcome());
        assertEquals(r.corners(), SceneFingerprint.fromBytes(Files.readAllBytes(target)).keypointCount());
        assertEquals(1, audits.size());
        assertEquals("BACKDROP_RETAKEN", audits.get(0).type());
        assertEquals("admin-1", audits.get(0).fields().get("adminId"));
        assertEquals("none", audits.get(0).fields().get("oldSha256"), "there was nothing to replace");
    }

    @Test
    void theFingerprintItReplacesIsKeptAndNamedByHash(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("backdrop.bin");
        retake(photo(5, true), target);
        byte[] first = Files.readAllBytes(target);
        retake(photo(9, true), target);
        assertArrayEquals(first, Files.readAllBytes(dir.resolve("backdrop.bin.previous")), "the old one can be restored");
        assertFalse(java.util.Arrays.equals(first, Files.readAllBytes(target)));
        AuditEvent second = audits.get(1);
        assertNotEquals(second.fields().get("oldSha256"), second.fields().get("newSha256"));
        assertEquals(audits.get(0).fields().get("newSha256"), second.fields().get("oldSha256"),
                "the chain of fingerprints can be followed through the audit log");
    }

    @Test
    void aBlankWallChangesNothingAndIsAudited(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("backdrop.bin");
        retake(photo(5, true), target);
        byte[] before = Files.readAllBytes(target);
        BackdropRetake.Result r = retake(photo(5, false), target);
        assertEquals(BackdropRetake.Outcome.TOO_PLAIN, r.outcome());
        assertArrayEquals(before, Files.readAllBytes(target));
        assertEquals("BACKDROP_RETAKE_REFUSED", audits.get(1).type());
        assertEquals("too-plain", audits.get(1).fields().get("why"));
    }

    @Test
    void somethingThatIsNotAnImageChangesNothingAndIsAudited(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("backdrop.bin");
        BackdropRetake.Result r = retake(new byte[] {1, 2, 3}, target);
        assertEquals(BackdropRetake.Outcome.UNREADABLE, r.outcome());
        assertFalse(Files.exists(target));
        assertEquals("unreadable", audits.get(0).fields().get("why"));
    }

    @Test
    void withoutAnAdministratorAReasonOrTheOperatorsWordNothingHappens(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("backdrop.bin");
        byte[] ok = photo(5, true);
        assertThrows(IllegalArgumentException.class,
                () -> BackdropRetake.retake(ok, target, " ", "reason", true, clock, audits::add));
        assertThrows(IllegalArgumentException.class,
                () -> BackdropRetake.retake(ok, target, "admin-1", "", true, clock, audits::add));
        assertThrows(IllegalArgumentException.class,
                () -> BackdropRetake.retake(ok, target, "admin-1", "reason", false, clock, audits::add));
        assertFalse(Files.exists(target));
        assertTrue(audits.isEmpty());
    }
}
