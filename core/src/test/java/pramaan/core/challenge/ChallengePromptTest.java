/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.policy.ChallengeKind;

/** Build Map #47: every kind has an id, an animation hint and an audio hint; no user-facing text in core. */
class ChallengePromptTest {

    @Test
    void everyKindHasACompletePrompt() {
        for (ChallengeKind kind : ChallengeKind.values()) {
            ChallengePrompt p;
            switch (kind) {
                case TURN:
                    p = ChallengePrompt.of(Challenge.turn(TurnDirection.RIGHT, 25), AccessibilityAdjustments.NONE);
                    break;
                case BLINK:
                    p = ChallengePrompt.of(Challenge.blink(2), AccessibilityAdjustments.NONE);
                    break;
                case EYES_CLOSED:
                    p = ChallengePrompt.of(Challenge.eyesClosed(1500), AccessibilityAdjustments.NONE);
                    break;
                case SMILE:
                    p = ChallengePrompt.of(Challenge.smile(), AccessibilityAdjustments.NONE);
                    break;
                case GAZE:
                    p = ChallengePrompt.of(Challenge.gaze(4), AccessibilityAdjustments.NONE);
                    break;
                case FLASH:
                    p = ChallengePrompt.flash(new FlashSequence(java.util.List.of(FlashColour.BLUE), new int[] {400}));
                    break;
                default:
                    throw new AssertionError("add a prompt for " + kind);
            }
            assertTrue(p.messageId().startsWith("pramaan.challenge."), kind + " id");
            assertFalse(p.animationHint().isEmpty(), kind + " animation");
            assertFalse(p.audioHint().isEmpty(), kind + " audio");
            assertFalse(p.messageId().contains(" "), "ids, not sentences");
        }
    }

    @Test
    void parametersTravelWithTheIdAndBlindResidentsGetToneGuidedTurns() {
        ChallengePrompt turn = ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20), AccessibilityAdjustments.NONE);
        assertEquals("LEFT", turn.params().get("direction"));
        assertEquals("20", turn.params().get("angleDeg"));
        assertEquals("anim:head-turn-left", turn.animationHint());
        assertFalse(turn.audioHint().contains("tone-guide"));
        ChallengePrompt guided = ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20),
                AccessibilityAdjustments.of(EnumSet.of(AccessibilityProfile.NO_VISION)));
        assertTrue(guided.audioHint().contains("tone-guide:turn-left"));
        assertEquals("1500", ChallengePrompt.of(Challenge.eyesClosed(1500), AccessibilityAdjustments.NONE).params().get("holdMs"));
    }
}
