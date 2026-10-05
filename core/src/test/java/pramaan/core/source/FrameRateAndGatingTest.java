/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.testing.TestClock;

/** Build Map #14: at 3, 10 and 30 fps the gates flip as specified, and 3 fps degrades rather than fails. */
class FrameRateAndGatingTest {

    private static final List<ChallengeKind> RESIDENT_POOL = List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED,
            ChallengeKind.BLINK, ChallengeKind.SMILE, ChallengeKind.GAZE, ChallengeKind.FLASH);

    private static double measure(double fps) {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        FrameRateMeter meter = new FrameRateMeter();
        Duration interval = Duration.ofNanos(Math.round(1e9 / fps));
        for (int i = 0; i < 100 && !meter.settled(); i++) {
            meter.onFrame(clock.instant());
            clock.advance(interval);
        }
        return meter.fps().orElseThrow();
    }

    private static DeviceCapabilities device(Capability... caps) {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        set.addAll(List.of(caps));
        return new DeviceCapabilities(new DeviceMetadata("3", "0", "code", DeviceLevel.L0, "mock", "face", "1"), set);
    }

    @Test
    void theMeterWaitsForAFullSecondThenMeasures() {
        FrameRateMeter meter = new FrameRateMeter();
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        meter.onFrame(clock.instant());
        meter.onFrame(clock.advanceMillis(500).instant());
        assertFalse(meter.settled());
        assertEquals(3.0, measure(3), 0.05);
        assertEquals(10.0, measure(10), 0.05);
        assertEquals(30.0, measure(30), 0.05);
    }

    @Test
    void atThreeFpsBlinkBecomesEyesClosedAndLatencyRulesGoInert() {
        SessionCapabilities s = SessionCapabilities.of(measure(3), device(Capability.RESIDENT_DISPLAY));
        assertFalse(s.blinkResolvable());
        assertFalse(s.earlyOnsetRuleEnabled());
        assertTrue(s.flashAllowed());
        assertEquals(Duration.ofMillis(700), s.flashMinStep());
        assertEquals(5, s.flashSteps());
        assertFalse(s.pursuitAllowed());
        assertFalse(s.saccadeAllowed());
        assertEquals(List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED, ChallengeKind.SMILE, ChallengeKind.GAZE,
                ChallengeKind.FLASH), s.effectivePool(RESIDENT_POOL));
    }

    @Test
    void atTenFpsBlinkAndLatencyChecksComeBack() {
        SessionCapabilities s = SessionCapabilities.of(measure(10), device(Capability.RESIDENT_DISPLAY));
        assertTrue(s.blinkResolvable());
        assertTrue(s.earlyOnsetRuleEnabled());
        assertEquals(Duration.ofMillis(350), s.flashMinStep());
        assertEquals(8, s.flashSteps());
        assertFalse(s.pursuitAllowed());
        assertTrue(s.effectivePool(RESIDENT_POOL).contains(ChallengeKind.BLINK));
    }

    @Test
    void atThirtyFpsEyeMovementChallengesUnlockOnlyWithAResidentFacingDisplay() {
        SessionCapabilities withDisplay = SessionCapabilities.of(measure(30), device(Capability.RESIDENT_DISPLAY));
        assertTrue(withDisplay.pursuitAllowed());
        assertTrue(withDisplay.saccadeAllowed());

        SessionCapabilities noDisplay = SessionCapabilities.of(measure(30), device());
        assertFalse(noDisplay.saccadeAllowed());
        assertFalse(noDisplay.flashAllowed());
        assertEquals(List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED, ChallengeKind.BLINK, ChallengeKind.SMILE),
                noDisplay.effectivePool(RESIDENT_POOL));
    }

    @Test
    void rppgNeedsUncompressedFramesWhatever_TheFrameRate() {
        assertFalse(SessionCapabilities.of(30, device(Capability.RESIDENT_DISPLAY)).rppgAllowed());
        assertTrue(SessionCapabilities.of(30, device(Capability.UNCOMPRESSED_FRAMES)).rppgAllowed());
    }

    @Test
    void theQueueDropsTheOldestAndCountsIt() throws Exception {
        DropOldestQueue<Integer> q = new DropOldestQueue<>(3);
        for (int i = 1; i <= 5; i++) {
            q.offer(i);
        }
        assertEquals(2, q.droppedCount());
        assertEquals(3, q.poll(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        q.close();
        assertFalse(q.offer(9));
        assertEquals(4, q.poll(1, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
}
