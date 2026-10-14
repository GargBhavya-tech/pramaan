/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.binding.EmissionLog;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.source.Capability;
import pramaan.core.testing.TestClock;

/** SBI Liveness Profile, addition 5 (Bible Part 41): the device-signed record of what its light showed. */
class EmissionLogTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TXN = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static KeyPair device;
    private static KeyPair stranger;
    private static FlashSequence asked;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        device = gen.generateKeyPair();
        stranger = gen.generateKeyPair();
        asked = ChallengeGenerator.replay(ChallengeGeneratorTest.seed(5)).plan(ChallengeGeneratorTest.RESIDENT_POOL, 1, 3,
                ChallengeGeneratorTest.caps(10, Capability.RESIDENT_DISPLAY), AccessibilityAdjustments.NONE)
                .flash().orElseThrow();
    }

    private static JwsTrust trust() {
        return JwsTrust.builder(TestClock.at("2026-10-14T10:00:00Z")).key("dev", device.getPublic()).build();
    }

    /** The log a faithful device would sign for {@code seq}, every scalar a string. */
    private static ObjectNode faithful(FlashSequence seq, String txn) {
        ObjectNode log = JSON.createObjectNode();
        log.put("version", "0.1");
        log.put("transactionId", txn);
        ArrayNode list = log.putArray("emissions");
        long at = 0;
        for (int i = 0; i < seq.colours().size(); i++) {
            ObjectNode e = list.addObject();
            e.put("colour", seq.colours().get(i).name());
            e.put("atMs", Long.toString(at));
            e.put("durationMs", Integer.toString(seq.durationsMs()[i]));
            at += seq.durationsMs()[i];
        }
        return log;
    }

    private static String sign(ObjectNode log, KeyPair key) throws Exception {
        return Jws.signRs256(JSON.writeValueAsBytes(log), "dev", key.getPrivate());
    }

    @Test
    void aFaithfulSignedLogVerifiesAndMatchesTheSchedule() throws Exception {
        EmissionLog log = EmissionLog.verify(sign(faithful(asked, TXN), device), trust(), TXN);
        assertEquals(asked.colours().size(), log.emissions().size());
        assertNull(log.differenceFrom(asked, 150));
        long total = 0;
        for (int d : asked.durationsMs()) {
            total += d;
        }
        assertEquals(total, log.totalMs());
    }

    @Test
    void colourAtFollowsTheFirstEmissionNotTheClockOfTheRequest() throws Exception {
        EmissionLog log = EmissionLog.verify(sign(faithful(asked, TXN), device), trust(), TXN);
        assertTrue(log.colourAt(T0, T0.minusMillis(1)).isEmpty(), "dark before the first emission");
        assertEquals(asked.colours().get(0), log.colourAt(T0, T0).orElseThrow());
        assertEquals(asked.colours().get(1), log.colourAt(T0, T0.plusMillis(asked.durationsMs()[0])).orElseThrow());
        assertTrue(log.colourAt(T0, T0.plusMillis(log.totalMs())).isEmpty(), "dark after the last");
    }

    @Test
    void aLogSignedByAStrangerOrForAnotherTransactionIsRefused() throws Exception {
        assertThrows(JwsVerificationException.class,
                () -> EmissionLog.verify(sign(faithful(asked, TXN), stranger), trust(), TXN));
        assertThrows(JwsVerificationException.class,
                () -> EmissionLog.verify(sign(faithful(asked, "fedcba9876543210fedcba9876543210"), device), trust(), TXN));
    }

    @Test
    void aTamperedLogIsRefused() throws Exception {
        String[] parts = sign(faithful(asked, TXN), device).split("\\.");
        ObjectNode other = faithful(asked, TXN);
        ((ObjectNode) other.path("emissions").get(0)).put("durationMs", "9999");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(other));
        assertThrows(JwsVerificationException.class,
                () -> EmissionLog.verify(parts[0] + "." + forged + "." + parts[2], trust(), TXN));
    }

    @Test
    void overlappingLateStartingUnknownColouredOrNumericEmissionsAreRefused() throws Exception {
        ObjectNode overlap = faithful(asked, TXN);
        ((ObjectNode) overlap.path("emissions").get(1)).put("atMs", "1");
        assertThrows(JwsVerificationException.class, () -> EmissionLog.verify(sign(overlap, device), trust(), TXN));

        ObjectNode late = faithful(asked, TXN);
        ((ObjectNode) late.path("emissions").get(0)).put("atMs", "200");
        assertThrows(JwsVerificationException.class, () -> EmissionLog.verify(sign(late, device), trust(), TXN));

        ObjectNode unknown = faithful(asked, TXN);
        ((ObjectNode) unknown.path("emissions").get(0)).put("colour", "RED");
        assertThrows(JwsVerificationException.class, () -> EmissionLog.verify(sign(unknown, device), trust(), TXN));
    }

    @Test
    void aDeviceThatStretchedAStepIsNamedForIt() throws Exception {
        ObjectNode slow = faithful(asked, TXN);
        ArrayNode list = (ArrayNode) slow.path("emissions");
        ((ObjectNode) list.get(0)).put("durationMs", Integer.toString(asked.durationsMs()[0] + 400));
        for (int i = 1; i < list.size(); i++) {
            ObjectNode e = (ObjectNode) list.get(i);
            e.put("atMs", Long.toString(Long.parseLong(e.path("atMs").asText()) + 400));
        }
        EmissionLog stretched = EmissionLog.verify(sign(slow, device), trust(), TXN);
        String difference = stretched.differenceFrom(asked, 150);
        assertNotNull(difference);
        assertTrue(difference.contains("lasted"), difference);
    }

    @Test
    void aDeviceThatPlayedAnotherNumberOfStepsOrAnotherColourIsNamedForIt() throws Exception {
        ObjectNode shorter = faithful(asked, TXN);
        ((ArrayNode) shorter.path("emissions")).remove(shorter.path("emissions").size() - 1);
        EmissionLog fewer = EmissionLog.verify(sign(shorter, device), trust(), TXN);
        assertTrue(fewer.differenceFrom(asked, 150).contains("steps"), fewer.differenceFrom(asked, 150));

        ObjectNode recoloured = faithful(asked, TXN);
        String was = recoloured.path("emissions").get(0).path("colour").asText();
        ((ObjectNode) recoloured.path("emissions").get(0)).put("colour", "WHITE".equals(was) ? "BLUE" : "WHITE");
        EmissionLog other = EmissionLog.verify(sign(recoloured, device), trust(), TXN);
        assertTrue(other.differenceFrom(asked, 150).contains("not"), other.differenceFrom(asked, 150));
    }
}
