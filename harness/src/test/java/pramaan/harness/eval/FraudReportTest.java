/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.audit.AuditEvent;
import pramaan.core.audit.AuditLog;
import pramaan.core.audit.FraudAnalytics;
import pramaan.core.audit.FraudAnalytics.Signal;
import pramaan.core.testing.TestClock;

class FraudReportTest {

    @TempDir
    Path dir;

    private static void attempt(AuditLog log, String session, String op, String outcome, String profiles) throws Exception {
        log.append(AuditEvent.of("ATTEMPT_ENDED").put("sessionId", session).put("operatorId", op).put("centreId", "C1")
                .put("outcome", outcome).put("accessibility", profiles).build());
    }

    @Test
    void readsTheAuditLogAndFlagsTheOutlier() throws Exception {
        Path audit = dir.resolve("audit.log");
        AuditLog log = AuditLog.open(audit, TestClock.at("2026-09-26T10:00:00Z"));
        for (int op = 0; op < 9; op++) {
            for (int i = 0; i < 100; i++) {
                attempt(log, "s" + op + "-" + i, "op" + op, i < 2 ? "REJECT_PAD" : "ACCEPTED", "");
            }
        }
        for (int i = 0; i < 40; i++) {
            attempt(log, "m" + i, "mallory", i < 6 ? "REJECT_VETO" : "ACCEPTED", "");
        }
        attempt(log, "x", "op0", "REJECT_BINDING", "LOW_MOBILITY");
        log.append(AuditEvent.of("EXCEPTION_REQUEST").put("sessionId", "x").put("outcome", "APPROVED").build());

        List<FraudAnalytics.Attempt> attempts = FraudReport.read(audit);
        assertEquals(941, attempts.size());

        Path out = dir.resolve("fraud.md");
        assertEquals(0, FraudReport.run(new String[] {"--audit", audit.toString(), "--out", out.toString()}));
        String md = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        assertTrue(md.contains("Audit chain intact: 942 entries"), md);
        assertTrue(md.contains("| mallory | C1 | PAD_FAIL | 6 | 40 |"), md);
        assertTrue(md.lines().filter(l -> l.contains("**REVIEW**")).allMatch(l -> l.startsWith("| mallory | C1 | PAD_FAIL")), md);
        assertTrue(md.contains("| op0 | C1 | EXCEPTION | 1 | 101 |"), md);
        assertTrue(md.contains("| op0 | C1 | BINDING_FAIL | 1 | 101 |"), md);
        for (Signal s : Signal.values()) {
            assertTrue(Files.readString(dir.resolve("fraud-funnel-" + s.name().toLowerCase(java.util.Locale.ROOT) + ".svg"))
                    .startsWith("<svg"));
        }
    }

    @Test
    void aBrokenChainIsSaidOutLoud() throws Exception {
        Path audit = dir.resolve("audit.log");
        AuditLog log = AuditLog.open(audit, TestClock.at("2026-09-26T10:00:00Z"));
        attempt(log, "a", "op", "ACCEPTED", "");
        attempt(log, "b", "op", "ACCEPTED", "");
        List<String> lines = Files.readAllLines(audit);
        Files.write(audit, List.of(lines.get(0).replace("ACCEPTED", "REJECT_PAD"), lines.get(1)));
        Path out = dir.resolve("fraud.md");
        assertEquals(0, FraudReport.run(new String[] {"--audit", audit.toString(), "--out", out.toString()}));
        assertTrue(Files.readString(out).contains("Audit chain BROKEN"));
    }
}
