/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.policy.OperatingPointTable;
import pramaan.harness.eval.PadReport;
import pramaan.harness.eval.SessionRow;

/**
 * Build Map #67: a corpus run completes unattended through the production engine and writes one row per clip.
 * The clips here are the stock MockMDS stream (a real face, but too small and lit from one side, so the quality
 * gate never lets it through) and a blank one: both end without a usable face, and the rows say why.
 */
class CorpusRunnerTest {

    private static final Path CONFIG = Path.of("..", "config");
    private static final Path MODELS = Path.of("..", "models");

    private static boolean modelsPresent() throws Exception {
        for (String name : OperatingPointTable.modelNames(Files.readAllBytes(CONFIG.resolve("operating-points.json")), "t")) {
            if (!Files.exists(MODELS.resolve(name))) {
                return false;
            }
        }
        return true;
    }

    @Test
    void aCorpusRunCompletesUnattendedWithOneRowPerClip(@TempDir Path dir) throws Exception {
        assumeTrue(modelsPresent(), "models/ not downloaded (gitignored)");
        assumeTrue(Files.exists(Path.of("..", "sbi", "testdata", "stream-sample.mjpeg")),
                "MockMDS stream fixture absent (gitignored: it shows a real person)");
        Files.createDirectories(dir.resolve("clips/blank"));
        Files.copy(Path.of("..", "sbi", "testdata", "stream-sample.mjpeg"), dir.resolve("clips/replay.mjpeg"));
        for (int i = 0; i < 5; i++) {
            BufferedImage gray = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    gray.setRGB(x, y, 0x808080);
                }
            }
            ImageIO.write(gray, "jpg", dir.resolve("clips/blank/" + i + ".jpg").toFile());
        }
        Path manifest = dir.resolve("manifest.csv");
        Files.writeString(manifest, "clip,label,pais,person,lighting,eyewear,device,fps\n"
                + "clips/replay.mjpeg,attack,screen-phone-video,P02,good,none,mockmds,10\n"
                + "clips/blank,bona_fide,,P03,dim,none,synthetic,10\n");
        Path out = dir.resolve("out/rows.jsonl");
        Path audit = dir.resolve("out/audit.log");

        RunCorpus.Summary s = RunCorpus.runAll(manifest, out, ActionLibrary.empty(), CONFIG, MODELS, audit,
                new CorpusRunner.Options().fps(3));

        List<SessionRow> rows = SessionRow.readJsonl(out);
        assertEquals(2, s.rows);
        assertEquals(2, rows.size(), "one row per clip");
        SessionRow replay = rows.get(0);
        SessionRow blank = rows.get(1);
        assertEquals("clips/replay.mjpeg", replay.id());
        assertEquals("screen-phone-video", replay.pais());
        for (SessionRow r : rows) {
            assertEquals(SessionRow.Outcome.NO_RESPONSE, r.outcome(), r.id());
            assertEquals("NO_USABLE_FACE", r.attributes().get("failure"), r.id());
            int frames = Integer.parseInt(r.attributes().get("frames"));
            assertTrue(frames >= 60 && frames <= 61, "one attempt: 20 s of acquiring at 3 fps, then stop; ran " + frames);
        }
        assertTrue(Set.of("MOVE_CLOSER", "EVEN_LIGHTING").contains(replay.attributes().get("lastGuidance")),
                "a face was found and the row says why it was not usable: " + replay.attributes());
        assertEquals("LOOK_AT_THE_CAMERA", blank.attributes().get("lastGuidance"));

        JsonNode run = new ObjectMapper().readTree(Files.readAllBytes(Path.of(out + ".run.json")));
        assertEquals(2, run.path("rows").asInt());
        assertNotEquals(run.path("policySha256Shipped").asText(), run.path("policySha256Run").asText(),
                "the diagnostic channel was switched on in memory, and the record says so");
        assertEquals(OperatingPointTable.modelNames(Files.readAllBytes(CONFIG.resolve("operating-points.json")), "t").size(),
                run.path("models").size());
        String log = new String(Files.readAllBytes(audit), StandardCharsets.UTF_8);
        assertTrue(log.contains("DIAGNOSTIC_MODE_ACTIVATED"), "reading scores is an audited diagnostic use");

        PadReport report = PadReport.compute(rows);
        assertFalse(report.pais().isEmpty(), "the report code reads the rows");
    }

    @Test
    void theActionLibraryAnswersEachPromptWithTheMatchingRecording(@TempDir Path dir) throws Exception {
        for (String a : new String[] {"left", "blink"}) {
            Files.createDirectories(dir.resolve(a));
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", dir.resolve(a + "/0.jpg").toFile());
            ImageIO.write(img, "jpg", dir.resolve(a + "/1.jpg").toFile());
        }
        Path csv = dir.resolve("actions.csv");
        Files.writeString(csv, "person,action,clip\nP01,TURN_LEFT,left\nP01,BLINK,blink\n");
        ActionLibrary lib = ActionLibrary.load(csv);
        assertEquals(2, lib.answer("P01", ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20),
                AccessibilityAdjustments.NONE)).orElseThrow().size());
        assertTrue(lib.answer("P01", ChallengePrompt.of(Challenge.turn(TurnDirection.RIGHT, 20),
                AccessibilityAdjustments.NONE)).isEmpty(), "a left turn never answers a right-turn prompt");
        assertEquals(6, lib.answer("P01", ChallengePrompt.of(Challenge.blink(3), AccessibilityAdjustments.NONE))
                .orElseThrow().size(), "one recorded blink, played three times");
        assertTrue(lib.answer("P02", ChallengePrompt.of(Challenge.smile(), AccessibilityAdjustments.NONE)).isEmpty());
        assertTrue(lib.has("P01"));
        assertFalse(lib.has("P02"));
    }
}
