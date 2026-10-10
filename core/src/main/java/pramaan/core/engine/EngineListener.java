/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.quality.Guidance;
import pramaan.core.session.SessionState;

/**
 * What a capture screen may know (Bible Part 19): states, guidance, message ids and prompts. Never a score,
 * a zone, a cue or a threshold — a screen that cannot see them cannot leak them, or vary its wording by them.
 * Every method is called on the engine's thread; a screen hands the event to its own UI thread.
 */
public interface EngineListener {

    EngineListener NONE = new EngineListener() {
    };

    default void stateChanged(SessionState state) {
    }

    /** Framing advice for the current frame; {@link Guidance#NONE} once the face is usable. Sent on change only. */
    default void guidance(Guidance guidance) {
    }

    /** A Bible Part 22 message id for the session's situation (a failure, a lockout); sent on change only. */
    default void message(String messageId) {
    }

    /**
     * Show a challenge prompt now.
     *
     * @param step 0-based
     * @param steps how many steps this plan has
     */
    default void prompt(ChallengePrompt prompt, int step, int steps) {
    }

    /**
     * The step just shown was verified: "Action detected" (Part 19.2), and the cue for the tone an eyes-closed
     * prompt promises — without it a resident cannot know when to open their eyes.
     */
    default void stepCompleted(int step, int steps) {
    }

    /** A new attempt began: "Attempt 2 of 3" (Part 19.2). Sent for every attempt after the first. */
    default void attempt(int attempt, int maxAttempts) {
    }

    /**
     * A failed attempt must wait before the next (Bible Part 12: 2, 4, 8 s...). The wait depends only on how many
     * attempts were used, never on a score, so "please wait" is safe to show (Build Map #93).
     */
    default void cooldown(long waitMillis) {
    }
}
