/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TestClockTest {

    @Test
    void thirtySecondsOfSimulatedTimeTakeNoRealTime() {
        TestClock clock = TestClock.at("2026-09-22T10:00:00Z");
        long realStart = System.nanoTime();

        clock.advance(Duration.ofSeconds(30));

        long realMicros = (System.nanoTime() - realStart) / 1_000;
        assertEquals(Instant.parse("2026-09-22T10:00:30Z"), clock.instant());
        assertTrue(realMicros < 50_000, "advancing should be effectively instant, took " + realMicros + " us");
    }

    @Test
    void timeStandsStillUntilAdvanced() {
        TestClock clock = TestClock.at("2026-09-22T10:00:00Z");
        assertEquals(clock.instant(), clock.instant());
        assertEquals(1_000L, clock.advanceMillis(1_000).millis() - Instant.parse("2026-09-22T10:00:00Z").toEpochMilli());
    }

    @Test
    void refusesToGoBackwards() {
        assertThrows(IllegalArgumentException.class, () -> TestClock.at("2026-09-22T10:00:00Z").advance(Duration.ofMillis(-1)));
    }
}
