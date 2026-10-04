/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build Map #1: the engine must not depend on UI toolkits, Android, networking, or the other
 * PRAMAAN modules. If core cannot be tested headlessly, the Android port (Phase 12) becomes a
 * rewrite.
 */
class CoreIsolationTest {

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "javafx.",
            "android.",
            "java.awt.",
            "javax.swing.",
            "javax.imageio.",
            "java.net.",
            "javax.net.",
            "pramaan.sbi.",
            "pramaan.desktop.",
            "pramaan.harness.");

    @Test
    void coreSourcesHaveNoUiDeviceOrNetworkImports() throws IOException {
        Path sources = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sources), "expected the core module as working directory");

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(sources)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertFalse(javaFiles.isEmpty(), "no core sources found; the check would pass vacuously");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                String imported = trimmed.substring("import ".length()).replace("static ", "");
                for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                    if (imported.startsWith(prefix)) {
                        violations.add(sources.relativize(file) + ": " + trimmed);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "core must stay headless; forbidden imports:\n" + String.join("\n", violations));
    }
}
