/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The no-hardware showcase: a script of held screens with captions and narration, rendered from the real UI code. */
class UiShowcaseTest {

    @Test
    void theScriptHasUniqueNamedBeatsEachWithACaptionAndANarrationLine() {
        List<UiShowcase.Beat> beats = UiShowcase.script();
        assertTrue(beats.size() >= 12, "beats: " + beats.size());
        Set<String> ids = new HashSet<>();
        for (UiShowcase.Beat b : beats) {
            assertTrue(ids.add(b.id), "duplicate beat id " + b.id);
            assertFalse(b.caption.isBlank(), b.id + " has no caption");
            assertFalse(b.narration.isBlank(), b.id + " has no narration");
            assertTrue(b.seconds >= 3 && b.seconds <= 10, b.id + " is held for " + b.seconds + " s");
        }
    }

    @Test
    void theWholeWalkThroughRunsForAboutAMinuteAndAHalf() {
        double total = UiShowcase.script().stream().mapToDouble(b -> b.seconds).sum();
        assertTrue(total >= 60 && total <= 120, "total " + total + " s");
    }

    @Test
    void theShowcaseIsAlwaysLabelledSimulatedAndNeverClaimsToBeALivenessRun() {
        for (UiShowcase.Beat b : UiShowcase.script()) {
            String all = (b.caption + " " + b.narration).toLowerCase(java.util.Locale.ROOT);
            assertFalse(all.contains("live camera"), b.id);
            assertFalse(all.contains("accuracy") || all.contains("apcer") || all.contains("bpcer"), b.id);
        }
    }

    @Test
    void renderingWritesOnePngPerBeatAndAMatchingIndex() throws Exception {
        Path dir = Files.createTempDirectory("pramaan-showcase");
        List<String> files;
        try {
            files = UiShowcase.render(dir);
        } catch (RuntimeException | UnsatisfiedLinkError noDisplay) {
            assumeTrue(false, "no JavaFX toolkit on this machine: " + noDisplay);
            return;
        }
        assertEquals(UiShowcase.script().size(), files.size());
        for (String f : files) {
            Path p = dir.resolve(f);
            assertTrue(Files.size(p) > 5_000, f + " is too small to be a rendered screen");
        }
        JsonNode index = new ObjectMapper().readTree(dir.resolve("beats.json").toFile());
        assertEquals(files.size(), index.size());
        assertEquals(files.get(0), index.get(0).path("file").asText());
        assertFalse(index.get(0).path("narration").asText().isEmpty());
    }
}
