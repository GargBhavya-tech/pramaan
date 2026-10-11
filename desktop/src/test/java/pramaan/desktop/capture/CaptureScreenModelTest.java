/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.quality.Guidance;
import pramaan.core.session.LivenessError;
import pramaan.core.session.SessionState;

/** Build Map #75: the capture screen's rules, headless (Bible Part 19). */
class CaptureScreenModelTest {

    /** Words and shapes that would betray engine internals on a resident's screen. */
    static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)score|probab|confiden|zone|cue|threshold|moir|spoof|fake|attack|replay|tau|p_?live|logit|%|\\d+\\.\\d+");

    private final Messages en = Messages.forLocale(Locale.ROOT);
    private final List<CaptureScreenModel.Screen> screens = new ArrayList<>();
    private final CaptureScreenModel model = new CaptureScreenModel(screens::add);

    private CaptureScreenModel.Screen last() {
        return screens.get(screens.size() - 1);
    }

    @Test
    void everyMessageTheEngineCanSendHasWordsAndNoneOfThemLeakAnything() {
        List<Text> all = new ArrayList<>();
        for (LivenessError e : LivenessError.values()) {
            e.messageId().ifPresent(id -> all.add(Text.of(id)));
        }
        for (Guidance g : Guidance.values()) {
            g.messageId().ifPresent(id -> all.add(Text.of(id)));
        }
        for (TurnDirection d : TurnDirection.values()) {
            all.add(prompt(Challenge.turn(d, 20)));
        }
        for (int n = 1; n <= 3; n++) {
            all.add(prompt(Challenge.blink(n)));
        }
        all.add(prompt(Challenge.eyesClosed(1500)));
        all.add(prompt(Challenge.smile()));
        all.add(prompt(Challenge.gaze(3)));
        for (String id : new String[] {"connecting", "position", "checking", "capturing", "success", "action-detected",
            "scan", "retry"}) {
            all.add(Text.of("pramaan.status." + id));
        }
        for (Text t : all) {
            assertTrue(en.has(t.id()), "no words for " + t.id());
            String shown = en.resolve(t);
            assertFalse(shown.contains("{"), "unfilled parameter in '" + shown + "'");
            assertFalse(FORBIDDEN.matcher(shown).find(), "'" + shown + "' says too much");
        }
        assertEquals("Please turn your head to your left", en.resolve(prompt(Challenge.turn(TurnDirection.LEFT, 25))));
        assertEquals("Please blink once", en.resolve(prompt(Challenge.blink(1))));
        assertEquals("Please blink 3 times", en.resolve(prompt(Challenge.blink(3))));
    }

    private static Text prompt(Challenge c) {
        ChallengePrompt p = ChallengePrompt.of(c, AccessibilityAdjustments.NONE);
        return Text.of(p.messageId(), p.params());
    }

    @Test
    void theFlowReadsPositionThenCheckingThenThePromptThenSuccess() {
        assertEquals(CaptureScreenModel.Stage.CONNECTING, model.current().stage());
        model.stateChanged(SessionState.ACQUIRING);
        model.guidance(Guidance.MOVE_CLOSER);
        assertEquals("Centre your face in the oval", en.resolve(last().headline()));
        assertEquals("Please move closer", en.resolve(last().guidance()));
        model.guidance(Guidance.NONE);
        assertNull(last().guidance());
        model.stateChanged(SessionState.PASSIVE);
        assertEquals("Checking face liveness…", en.resolve(last().headline()));
        model.stateChanged(SessionState.CHALLENGING);
        ChallengePrompt turn = ChallengePrompt.of(Challenge.turn(TurnDirection.RIGHT, 20), AccessibilityAdjustments.NONE);
        model.prompt(turn, 0, 2);
        assertEquals("Please turn your head to your right", en.resolve(last().headline()));
        assertTrue(last().animation().startsWith("anim:head-turn"));
        model.stepCompleted(0, 2);
        assertEquals("Action detected", en.resolve(last().headline()));
        assertNull(last().animation(), "the prompt goes once its step is done");
        model.stateChanged(SessionState.CAPTURING);
        model.guidance(Guidance.HOLD_STILL);
        assertEquals("Hold still", en.resolve(last().headline()));
        assertNull(last().guidance(), "no framing advice while the device captures");
        model.stateChanged(SessionState.ACCEPTED);
        assertEquals(CaptureScreenModel.Stage.SUCCESS, last().stage());
        assertEquals("Good, face captured successfully", en.resolve(last().headline()));
    }

    @Test
    void theEyesClosedStepEndsWithTheTonePromisedInItsPrompt() {
        model.stateChanged(SessionState.CHALLENGING);
        model.prompt(ChallengePrompt.of(Challenge.eyesClosed(1000), AccessibilityAdjustments.NONE), 0, 1);
        assertFalse(last().tone());
        model.stepCompleted(0, 1);
        assertTrue(last().tone(), "without the tone a resident cannot know when to open their eyes");
        model.guidance(Guidance.NONE);
        assertFalse(last().tone(), "one tone, not one per update");
    }

    @Test
    void aRejectionReadsGenericallyAndStaysVisibleWhileTheResidentRepositions() {
        // PAD, veto (injection) and binding all map to one generic sentence (Part 19.2): the screen must not
        // teach an attacker which layer caught them.
        model.stateChanged(SessionState.FAILED);
        model.message("pramaan.error.not-completed");
        assertEquals("Face verification could not be completed. Please try again.", en.resolve(last().headline()));
        model.stateChanged(SessionState.ACQUIRING);
        model.attempt(2, 3);
        assertEquals("Attempt 2 of 3", en.resolve(last().attempt()));
        assertEquals("Face verification could not be completed. Please try again.", en.resolve(last().notice()));
    }

    @Test
    void aLockedResidentIsHandedToTheOperatorNotToldToContactSupport() {
        model.stateChanged(SessionState.RATE_LIMITED);
        assertEquals(CaptureScreenModel.Stage.STOPPED, last().stage());
        assertEquals("pramaan.error.locked", last().headline().id());
        model.engineUnavailable();
        assertEquals("Face verification is unavailable. Please contact support.", en.resolve(last().headline()));
        model.deviceUnavailable();
        assertEquals("Camera not connected. Please check the device.", en.resolve(last().headline()));
    }

    @Test
    void theCooldownWaitFollowsTheAttemptCountNeverAScore() {
        // Build Map #93: cooldown(waitMillis) sets the RETRY stage's notice, rounded up to whole seconds.
        model.stateChanged(SessionState.RETRY_WAIT);
        model.cooldown(1500);
        assertEquals(CaptureScreenModel.Stage.RETRY, last().stage());
        assertEquals("Please wait 2 seconds before trying again", en.resolve(last().notice()));

        model.cooldown(1000);
        assertEquals("Please wait 1 second before trying again", en.resolve(last().notice()), "the #1 special case");

        model.cooldown(1);
        assertEquals("Please wait 1 second before trying again", en.resolve(last().notice()), "rounds up, never to zero");
    }

    @Test
    void theCooldownNoticeIsClearedOnceTheSessionLeavesRetry() {
        model.stateChanged(SessionState.RETRY_WAIT);
        model.cooldown(4000);
        assertEquals("Please wait 4 seconds before trying again", en.resolve(last().notice()));
        model.stateChanged(SessionState.ACQUIRING);
        assertNull(last().notice(), "a stale wait must not survive into the next attempt's screen");
    }

    @Test
    void theSessionsOwnLookAtTheCameraIsGuidanceNotAFailureNotice() {
        model.stateChanged(SessionState.ACQUIRING);
        model.message(LivenessError.NO_FACE.messageId().orElseThrow());
        assertNull(last().notice());
        assertNotEquals("pramaan.guidance.look-at-camera", last().headline().id());
    }

    @Test
    void stagesFollowTheSessionStateMachine() {
        assertEquals(CaptureScreenModel.Stage.CHECKING, CaptureScreenModel.stageOf(SessionState.DECIDED));
        assertEquals(CaptureScreenModel.Stage.CAPTURING, CaptureScreenModel.stageOf(SessionState.BINDING));
        assertEquals(CaptureScreenModel.Stage.RETRY, CaptureScreenModel.stageOf(SessionState.RETRY_WAIT));
        for (SessionState s : new SessionState[] {SessionState.LOCKED_OUT, SessionState.EXCEPTION_FLOW,
            SessionState.UNAVAILABLE, SessionState.EXCEPTION_BLOCKED}) {
            assertEquals(CaptureScreenModel.Stage.STOPPED, CaptureScreenModel.stageOf(s), s.name());
        }
    }
}
