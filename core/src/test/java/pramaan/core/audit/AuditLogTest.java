/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.testing.TestClock;

/** Build Map #10: the audit log is tamper-evident and refuses biometric or personal data. */
class AuditLogTest {

    @TempDir
    Path dir;

    private Path writeThreeEvents() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        TestClock clock = TestClock.at("2026-09-23T09:00:00Z");
        AuditLog log = AuditLog.open(file, clock);
        log.append(AuditEvent.of("SESSION_STARTED").put("sessionId", "s-01").put("workflow", "RESIDENT").build());
        clock.advanceMillis(1_500);
        log.append(AuditEvent.of("PAD_DECISION").put("sessionId", "s-01").put("pLive", 0.98).put("zone", "ACCEPT").build());
        clock.advanceMillis(700);
        log.append(AuditEvent.of("BINDING_OK").put("sessionId", "s-01").put("similarity", 0.81).put("deltaMs", 700L).build());
        return file;
    }

    @Test
    void anUntouchedLogVerifies() throws IOException {
        Path file = writeThreeEvents();
        AuditLog.Verification v = AuditLog.verify(file);
        assertTrue(v.intact(), v.problem());
        assertEquals(3, v.entries());
        assertTrue(Files.readAllLines(file).get(1).contains("\"ts\":\"2026-09-23T09:00:01.500Z\""), "timestamps come from the injected clock");
    }

    @Test
    void alteringOneValueBreaksTheChainAtThatLine() throws IOException {
        Path file = writeThreeEvents();
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.set(1, lines.get(1).replace("\"zone\":\"ACCEPT\"", "\"zone\":\"REJECT\""));
        Files.write(file, lines, StandardCharsets.UTF_8);

        AuditLog.Verification v = AuditLog.verify(file);
        assertFalse(v.intact());
        assertEquals(2, v.brokenLine());
    }

    @Test
    void reorderingOrRemovingAMiddleLineIsDetected() throws IOException {
        Path file = writeThreeEvents();
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        Collections.swap(lines, 0, 1);
        Files.write(file, lines, StandardCharsets.UTF_8);
        assertFalse(AuditLog.verify(file).intact());

        Path other = writeThreeEventsInto("audit2.jsonl");
        List<String> kept = new ArrayList<>(Files.readAllLines(other, StandardCharsets.UTF_8));
        kept.remove(1);
        Files.write(other, kept, StandardCharsets.UTF_8);
        assertFalse(AuditLog.verify(other).intact());
    }

    @Test
    void reopeningContinuesTheChainAndABrokenLogIsRefused() throws IOException {
        Path file = writeThreeEvents();
        AuditLog reopened = AuditLog.open(file, TestClock.at("2026-09-23T10:00:00Z"));
        reopened.append(AuditEvent.of("SESSION_STARTED").put("sessionId", "s-02").build());
        AuditLog.Verification v = AuditLog.verify(file);
        assertTrue(v.intact(), v.problem());
        assertEquals(4, v.entries());

        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.set(0, lines.get(0).replace("s-01", "s-99"));
        Files.write(file, lines, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> AuditLog.open(file, TestClock.at("2026-09-23T11:00:00Z")));
    }

    @Test
    void biometricAndPersonalDataAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> AuditEvent.of("X").put("faceImage", "AAAA"));
        assertThrows(IllegalArgumentException.class, () -> AuditEvent.of("X").put("bioValue", "AAAA"));
        assertThrows(IllegalArgumentException.class, () -> AuditEvent.of("X").put("name", "Ravi"));
        assertThrows(IllegalArgumentException.class,
                () -> AuditEvent.of("X").put("note", "A".repeat(AuditEvent.MAX_TEXT_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> AuditEvent.of("lower_case"));
        assertThrows(IllegalArgumentException.class, () -> AuditEvent.of("X").put("score", Double.NaN));
    }

    private Path writeThreeEventsInto(String name) throws IOException {
        Path file = dir.resolve(name);
        AuditLog log = AuditLog.open(file, TestClock.at("2026-09-23T09:00:00Z"));
        for (int i = 0; i < 3; i++) {
            log.append(AuditEvent.of("E").put("i", (long) i).build());
        }
        return file;
    }
}
