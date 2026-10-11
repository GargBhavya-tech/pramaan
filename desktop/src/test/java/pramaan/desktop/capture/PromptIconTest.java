/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.SessionCapabilities;

/** Build Map #91: every animation hint the engine can actually send has an icon (headless, no accessibility loss). */
class PromptIconTest {

    private static DeviceCapabilities device(Capability... caps) {
        return new DeviceCapabilities(new DeviceMetadata("d", "0", "code", DeviceLevel.L1, "mock", "face", "1"),
                caps.length == 0 ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(List.of(caps)));
    }

    @Test
    void everyEnumValueRoundTripsThroughItsHint() {
        for (PromptIcon icon : PromptIcon.values()) {
            assertEquals(icon, PromptIcon.of(icon.hint()).orElseThrow());
        }
    }

    @Test
    void anUnknownHintHasNoIcon() {
        assertTrue(PromptIcon.of("anim:does-not-exist").isEmpty());
        assertTrue(PromptIcon.of(null).isEmpty());
    }

    @Test
    void everyChallengeKindsPromptHasAnIcon() {
        assertTrue(PromptIcon.of(ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20), AccessibilityAdjustments.NONE)
                .animationHint()).isPresent());
        assertTrue(PromptIcon.of(ChallengePrompt.of(Challenge.turn(TurnDirection.RIGHT, 20), AccessibilityAdjustments.NONE)
                .animationHint()).isPresent());
        assertTrue(PromptIcon.of(ChallengePrompt.of(Challenge.blink(1), AccessibilityAdjustments.NONE).animationHint()).isPresent());
        assertTrue(PromptIcon.of(ChallengePrompt.of(Challenge.eyesClosed(1000), AccessibilityAdjustments.NONE).animationHint())
                .isPresent());
        assertTrue(PromptIcon.of(ChallengePrompt.of(Challenge.smile(), AccessibilityAdjustments.NONE).animationHint()).isPresent());
        assertTrue(PromptIcon.of(ChallengePrompt.of(Challenge.gaze(0), AccessibilityAdjustments.NONE).animationHint()).isPresent());

        // FLASH's prompt comes from a real generated plan, not a hand-built FlashSequence (its constructor is
        // package-private to core.challenge): a resident-facing device at flash-eligible fps.
        SessionCapabilities caps = SessionCapabilities.of(30, device(Capability.RESIDENT_DISPLAY));
        ChallengePlan plan = ChallengeGenerator.fresh(new SecureRandom()).plan(List.of(ChallengeKind.FLASH), 0, 0, caps,
                AccessibilityAdjustments.NONE);
        assertTrue(plan.flash().isPresent(), "flash-eligible capabilities must produce a flash sequence");
        assertTrue(PromptIcon.of(ChallengePrompt.flash(plan.flash().get()).animationHint()).isPresent());
    }
}
