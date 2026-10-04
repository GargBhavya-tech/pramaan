/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build Map #41: a predictable random source anywhere in the engine is a security bug (challenges, audit
 * draws, nonces). Only SecureRandom, or randomness derived from a SecureRandom seed, may be used.
 */
class NoInsecureRandomTest {

    private static final String[] FORBIDDEN = {"java.util.Random", "new Random(", "Math.random", "ThreadLocalRandom",
        "SplittableRandom"};

    @Test
    void coreNeverUsesAPredictableRandomSource() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                for (String bad : FORBIDDEN) {
                    if (src.contains(bad)) {
                        offences.add(f + ": " + bad);
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "insecure randomness in core: " + offences);
    }
}
