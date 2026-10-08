/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.SessionCapabilities;

/** Build Map #41, #42. */
class ChallengeGeneratorTest {

    static final List<ChallengeKind> RESIDENT_POOL = List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED,
            ChallengeKind.BLINK, ChallengeKind.SMILE, ChallengeKind.GAZE, ChallengeKind.FLASH);

    static SessionCapabilities caps(double fps, Capability... have) {
        Set<Capability> set = have.length == 0 ? EnumSet.noneOf(Capability.class) : EnumSet.of(have[0], have);
        return SessionCapabilities.of(fps, new DeviceCapabilities(
                new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1"), set));
    }

    /** Deterministic test seeds: SHA-256 of the index. */
    static byte[] seed(long i) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(8).putLong(i).array());
    }

    private static double chiSquare(long[] counts) {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        double expected = (double) total / counts.length;
        double x = 0;
        for (long c : counts) {
            x += (c - expected) * (c - expected) / expected;
        }
        return x;
    }

    @Test
    void everyParameterIsUniformOverOneHundredThousandDraws() throws Exception {
        SessionCapabilities full = caps(30, Capability.RESIDENT_DISPLAY);
        List<Challenge> options = ChallengeGenerator.options(full.effectivePool(RESIDENT_POOL), AccessibilityAdjustments.NONE);
        assertEquals(24, options.size(), "turn 8 + eyes-closed 3 + blink 3 + smile 1 + gaze 9");
        long[] option = new long[options.size()];
        long[] delay = new long[ChallengeGenerator.ONSET_DELAY_CHOICES];
        long[] stepCount = new long[3];
        long[] colour = new long[FlashColour.values().length];
        long[] duration = new long[4];
        for (int i = 0; i < 100_000; i++) {
            ChallengePlan plan = ChallengeGenerator.replay(seed(i)).plan(RESIDENT_POOL, 1, 3, full, AccessibilityAdjustments.NONE);
            Challenge first = plan.steps().get(0);
            option[options.indexOf(first.withOnsetDelay(0))]++;
            delay[(first.onsetDelayMs() - ChallengeGenerator.ONSET_DELAY_MIN_MS) / ChallengeGenerator.ONSET_DELAY_STEP_MS]++;
            stepCount[plan.steps().size() - 1]++;
            FlashSequence flash = plan.flash().orElseThrow();
            colour[flash.colours().get(0).ordinal()]++;
            duration[(flash.durationsMs()[0] - 350) / 50]++;
        }
        // Critical values at p = 0.001 for 23, 9, 2, 5 and 3 degrees of freedom.
        assertTrue(chiSquare(option) < 49.73, "option chi2 " + chiSquare(option));
        assertTrue(chiSquare(delay) < 27.88, "delay chi2 " + chiSquare(delay));
        assertTrue(chiSquare(stepCount) < 13.82, "step count chi2 " + chiSquare(stepCount));
        assertTrue(chiSquare(colour) < 20.52, "colour chi2 " + chiSquare(colour));
        assertTrue(chiSquare(duration) < 16.27, "duration chi2 " + chiSquare(duration));
    }

    @Test
    void aThreeStepSequenceCarriesAtLeastThirteenBitsOnlyWithTheFlash() throws Exception {
        ChallengePlan plan = ChallengeGenerator.replay(seed(7)).plan(RESIDENT_POOL, 3, 3,
                caps(30, Capability.RESIDENT_DISPLAY), AccessibilityAdjustments.NONE);
        assertEquals(3, plan.steps().size());
        // Outcomes over 24 options: turn L 4, turn R 4, blink 3, eyes-closed 3, smile 1, 9 gaze cells 1 each.
        double h = -(2 * (4 / 24.0) * log2(4 / 24.0) + 2 * (3 / 24.0) * log2(3 / 24.0) + 10 * (1 / 24.0) * log2(1 / 24.0));
        assertEquals(3.522, h, 0.001);
        assertEquals(3 * h, plan.actionBits(), 1e-9);
        assertTrue(plan.actionBits() < 13, "the actions alone are NOT enough: " + plan.actionBits());
        assertEquals(8 * log2(6), plan.flashBits(), 1e-9);
        assertTrue(plan.actionBits() + plan.flashBits() >= 13);
        assertFalse(plan.lowEntropy());
        ChallengePlan noFlash = ChallengeGenerator.replay(seed(7)).plan(
                List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED, ChallengeKind.BLINK, ChallengeKind.SMILE,
                        ChallengeKind.GAZE), 3, 3, caps(30, Capability.RESIDENT_DISPLAY), AccessibilityAdjustments.NONE);
        assertTrue(noFlash.lowEntropy());
    }

    @Test
    void aTurnCountsOnlyItsDirection() {
        assertEquals(1.0, ChallengeGenerator.outcomeEntropyBits(ChallengeGenerator.options(List.of(ChallengeKind.TURN),
                AccessibilityAdjustments.NONE)), 1e-12, "eight turn options, two verifiable outcomes");
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    @Test
    void theCommonDesktopResidentCaseIsFlaggedAsLowEntropy() throws Exception {
        // 3 fps, no resident-facing display: BLINK -> EYES_CLOSED, GAZE and FLASH drop out.
        ChallengePlan plan = ChallengeGenerator.replay(seed(1)).plan(RESIDENT_POOL, 3, 3, caps(3), AccessibilityAdjustments.NONE);
        assertEquals(12, plan.actionOptions(), "turn 8 + eyes-closed 3 + smile 1");
        assertFalse(plan.flash().isPresent());
        assertEquals(3 * 1.855, plan.actionBits(), 0.01);
        assertTrue(plan.lowEntropy(), plan.actionBits() + " bits must be flagged");
    }

    @Test
    void theCommittedSeedReproducesTheSequenceExactly() {
        ChallengeGenerator live = ChallengeGenerator.fresh(new SecureRandom());
        SessionCapabilities c = caps(30, Capability.RESIDENT_DISPLAY);
        ChallengePlan original = live.plan(RESIDENT_POOL, 1, 3, c, AccessibilityAdjustments.NONE);
        ChallengeGenerator server = ChallengeGenerator.replay(live.revealSeed());
        ChallengePlan again = server.plan(RESIDENT_POOL, 1, 3, c, AccessibilityAdjustments.NONE);
        assertEquals(original.seedCommitment(), again.seedCommitment());
        assertEquals(original.steps(), again.steps());
        assertEquals(original.flash(), again.flash());
        ChallengePlan other = ChallengeGenerator.fresh(new SecureRandom()).plan(RESIDENT_POOL, 3, 3, c, AccessibilityAdjustments.NONE);
        assertNotEquals(original.seedCommitment(), other.seedCommitment());
    }

    // ---------------------------------------------------------------- #42
    @Test
    void noProfileEverEmitsAForbiddenKindAndEveryProfileHasAPath() throws Exception {
        SessionCapabilities withDisplay = caps(30, Capability.RESIDENT_DISPLAY);
        SessionCapabilities desktop = caps(3);
        for (AccessibilityProfile profile : AccessibilityProfile.values()) {
            AccessibilityAdjustments adj = AccessibilityAdjustments.of(EnumSet.of(profile));
            for (SessionCapabilities c : List.of(withDisplay, desktop)) {
                for (int i = 0; i < 2_000; i++) {
                    ChallengePlan plan = ChallengeGenerator.replay(seed(i)).plan(RESIDENT_POOL, 1, 3, c, adj);
                    for (Challenge step : plan.steps()) {
                        assertTrue(adj.allows(step.kind()), profile + " emitted " + step);
                        if (profile == AccessibilityProfile.LIMITED_MOTION && step.kind() == ChallengeKind.TURN) {
                            assertEquals(15, step.targetDeg());
                        }
                    }
                    assertTrue(!plan.steps().isEmpty() || plan.flash().isPresent() || plan.needsFallback(),
                            profile + " left no path");
                }
            }
        }
    }

    @Test
    void anInfantWithoutAResidentFacingLightFallsThroughInsteadOfFailing() throws Exception {
        AccessibilityAdjustments infant = AccessibilityAdjustments.of(EnumSet.of(AccessibilityProfile.INFANT));
        ChallengePlan desktop = ChallengeGenerator.replay(seed(3)).plan(RESIDENT_POOL, 1, 3, caps(3), infant);
        assertTrue(desktop.steps().isEmpty());
        assertTrue(desktop.needsFallback(), "no action and no flash: no-action cues and the multimodal path");
        ChallengePlan kiosk = ChallengeGenerator.replay(seed(3)).plan(RESIDENT_POOL, 1, 3,
                caps(30, Capability.RESIDENT_DISPLAY), infant);
        assertTrue(kiosk.steps().isEmpty() && kiosk.flash().isPresent() && !kiosk.needsFallback());
    }

    @Test
    void profilesCombineAndOnlyEverRelax() {
        AccessibilityAdjustments both = AccessibilityAdjustments.of(EnumSet.of(AccessibilityProfile.FACIAL_MOTOR,
                AccessibilityProfile.LIMITED_MOTION));
        assertFalse(both.allows(ChallengeKind.SMILE));
        assertTrue(both.eitherEye());
        assertEquals(150, both.turnHoldMs());
        assertEquals(2, both.timeoutMultiplier());
        assertEquals(300, AccessibilityAdjustments.NONE.turnHoldMs());
    }

    // ---------------------------------------------------------------- cue-unlocking (Bible Part 10)

    private static final Set<ChallengeKind> UNLOCKING = EnumSet.of(ChallengeKind.TURN, ChallengeKind.SMILE);

    @Test
    void everyPlanContainsAStepThatUnlocksACueWhenThePoolAllowsOne() throws Exception {
        List<ChallengeKind> operatorPool = List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED, ChallengeKind.BLINK);
        int forcedAlone = 0;
        for (int i = 0; i < 2000; i++) {
            ChallengePlan plan = ChallengeGenerator.replay(seed(i)).plan(operatorPool, 2, 3, caps(30),
                    AccessibilityAdjustments.NONE, UNLOCKING);
            long unlocking = plan.steps().stream().filter(c -> UNLOCKING.contains(c.kind())).count();
            assertTrue(unlocking >= 1, "plan without an unlocking step: " + plan.steps());
            if (unlocking == 1) {
                forcedAlone++;
            }
            assertEquals(UNLOCKING, plan.unlockingKinds());
        }
        assertTrue(forcedAlone > 0, "the other steps are still drawn from the whole pool");
    }

    @Test
    void theForcedStepCountsOnlyItsOwnEntropy() throws Exception {
        List<ChallengeKind> pool = List.of(ChallengeKind.TURN, ChallengeKind.EYES_CLOSED, ChallengeKind.BLINK);
        ChallengePlan plan = ChallengeGenerator.replay(seed(5)).plan(pool, 3, 3, caps(30), AccessibilityAdjustments.NONE,
                UNLOCKING);
        double all = ChallengeGenerator.outcomeEntropyBits(ChallengeGenerator.options(pool, AccessibilityAdjustments.NONE));
        double turnOnly = ChallengeGenerator.outcomeEntropyBits(
                ChallengeGenerator.options(List.of(ChallengeKind.TURN), AccessibilityAdjustments.NONE));
        assertEquals(1.0, turnOnly, 1e-12, "a forced turn carries only its direction");
        assertEquals(2 * all + turnOnly, plan.actionBits(), 1e-9);
    }

    @Test
    void withNothingToUnlockThePlanIsExactlyTheUnconstrainedOne() throws Exception {
        List<ChallengeKind> eyesOnly = List.of(ChallengeKind.EYES_CLOSED, ChallengeKind.BLINK);
        for (int i = 0; i < 50; i++) {
            ChallengePlan plain = ChallengeGenerator.replay(seed(i)).plan(eyesOnly, 1, 3, caps(30), AccessibilityAdjustments.NONE);
            ChallengePlan asked = ChallengeGenerator.replay(seed(i)).plan(eyesOnly, 1, 3, caps(30),
                    AccessibilityAdjustments.NONE, UNLOCKING);
            assertEquals(plain.steps(), asked.steps(), "no unlocking option: nothing is forced");
            assertEquals(plain.actionBits(), asked.actionBits(), 1e-12);
        }
    }

    @Test
    void theServerRegeneratesAnUnlockingPlanFromTheRevealedSeed() {
        ChallengeGenerator live = ChallengeGenerator.fresh(new SecureRandom());
        ChallengePlan original = live.plan(RESIDENT_POOL, 1, 3, caps(10), AccessibilityAdjustments.NONE, UNLOCKING);
        ChallengePlan again = ChallengeGenerator.replay(live.revealSeed()).plan(RESIDENT_POOL, 1, 3, caps(10),
                AccessibilityAdjustments.NONE, original.unlockingKinds());
        assertEquals(original.steps(), again.steps());
    }
}
