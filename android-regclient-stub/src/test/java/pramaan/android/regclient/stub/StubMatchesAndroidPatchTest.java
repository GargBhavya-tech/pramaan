/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android.regclient.stub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The stand-ins in this module are copies of the classes the patch adds to MOSIP's Android Registration Client.
 * If the patch changes and a stand-in does not, the adapter would compile against an interface the real client does not have:
 * this test fails first.
 */
class StubMatchesAndroidPatchTest {

    private static final Path PATCH = Path.of("..", "integration", "android-registration-client", "0001-pramaan-android-liveness-hooks.patch");
    private static final Path STUBS = Path.of("src", "main", "java", "io", "mosip", "registration", "client", "liveness");

    private static Set<String> addedLines() throws IOException {
        Set<String> added = new HashSet<>();
        for (String line : Files.readAllLines(PATCH, StandardCharsets.UTF_8)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added.add(strip(line.substring(1)));
            }
        }
        return added;
    }

    private static String strip(String s) {
        return s.replace("\r", "");
    }

    @Test
    void everyLineOfEveryStandInIsAnAddedLineOfTheAndroidPatch() throws IOException {
        Set<String> added = addedLines();
        assertFalse(added.isEmpty(), "the Android patch was not found or is empty: " + PATCH.toAbsolutePath());
        for (String name : new String[] {"LivenessHook.java", "LivenessRefusedException.java", "CaptureTicket.java", "PromptListener.java", "LivenessHooks.java"}) {
            for (String line : Files.readAllLines(STUBS.resolve(name), StandardCharsets.UTF_8)) {
                String s = strip(line);
                assertTrue(added.contains(s), name + " has a line the Android patch does not add: '" + s + "'");
            }
        }
    }
}
