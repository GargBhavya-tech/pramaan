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
 * Build Map #8: engine logic never reads the system clock directly; it takes a {@link java.time.Clock}.
 * One hidden clock call makes the session-machine tests flaky and slow forever. Comments are ignored,
 * so documentation may still mention these calls.
 */
class NoWallClockInCoreTest {

    private static final List<String> FORBIDDEN_CALLS = List.of(
            "System.currentTimeMillis(",
            "System.nanoTime(",
            "Instant.now()",
            "LocalDateTime.now()",
            "LocalDate.now()",
            "LocalTime.now()",
            "ZonedDateTime.now()",
            "OffsetDateTime.now()",
            "new Date()",
            "Clock.systemUTC(",
            "Clock.systemDefaultZone(");

    @Test
    void coreMainSourcesTakeAClockInsteadOfReadingTheSystemClock() throws IOException {
        Path sources = Path.of("src", "main", "java");
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(sources)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertFalse(javaFiles.isEmpty(), "no core sources found; the check would pass vacuously");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String code = lines.get(i).trim();
                if (inBlockComment) {
                    if (code.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                if (code.startsWith("/*")) {
                    inBlockComment = !code.contains("*/");
                    continue;
                }
                int lineComment = code.indexOf("//");
                if (lineComment >= 0) {
                    code = code.substring(0, lineComment);
                }
                for (String call : FORBIDDEN_CALLS) {
                    if (code.contains(call)) {
                        violations.add(sources.relativize(file) + ":" + (i + 1) + " uses " + call);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "take a java.time.Clock instead of reading the system clock:\n" + String.join("\n", violations));
    }
}
