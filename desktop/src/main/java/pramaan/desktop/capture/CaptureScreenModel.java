/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.engine.EngineListener;
import pramaan.core.quality.Guidance;
import pramaan.core.session.SessionState;

/**
 * What the face capture screen shows, computed from engine events alone (Build Map #75; Bible Part 19). It has
 * no JavaFX in it, so the rules are tested headlessly, and it cannot show a score: the engine never sends one.
 * Each change produces a new immutable {@link Screen}, handed to the view on the engine's thread.
 *
 * <p>Rules: guidance only while the face is being positioned or checked; a prompt only during its step;
 * failures in the engine's generic wording, never their cause; "Action detected" between steps, with the tone
 * an eyes-closed prompt promised.
 */
public final class CaptureScreenModel implements EngineListener {

    /** Where the resident is in the flow; the view picks colours and icons from it, never from a score. */
    public enum Stage {
        CONNECTING,
        POSITIONING,
        CHECKING,
        CHALLENGE,
        CAPTURING,
        SUCCESS,
        RETRY,
        STOPPED
    }

    /** One frame of the screen. Nullable parts are simply not shown. */
    public static final class Screen {
        private final Stage stage;
        private final Text headline;
        private final Text guidance;
        private final Text notice;
        private final Text attempt;
        private final String animation;
        private final boolean tone;

        Screen(Stage stage, Text headline, Text guidance, Text notice, Text attempt, String animation, boolean tone) {
            this.stage = stage;
            this.headline = headline;
            this.guidance = guidance;
            this.notice = notice;
            this.attempt = attempt;
            this.animation = animation;
            this.tone = tone;
        }

        public Stage stage() {
            return stage;
        }

        public Text headline() {
            return headline;
        }

        public Text guidance() {
            return guidance;
        }

        public Text notice() {
            return notice;
        }

        public Text attempt() {
            return attempt;
        }

        /** The prompt's animation hint ("anim:head-turn-left"), while a prompt is up. */
        public String animation() {
            return animation;
        }

        /** Play the step-complete tone now (once). */
        public boolean tone() {
            return tone;
        }

        /** Every text on this screen, for checks that nothing sensitive is ever shown. */
        public List<Text> texts() {
            List<Text> out = new ArrayList<>();
            for (Text t : new Text[] {headline, guidance, notice, attempt}) {
                if (t != null) {
                    out.add(t);
                }
            }
            return Collections.unmodifiableList(out);
        }

        @Override
        public String toString() {
            return stage + " " + texts() + (animation == null ? "" : " " + animation) + (tone ? " +tone" : "");
        }
    }

    private final Consumer<Screen> sink;
    private Stage stage = Stage.CONNECTING;
    private Guidance guidance = Guidance.NONE;
    private String message;
    private Text attempt;
    private ChallengePrompt prompt;
    private boolean stepDone;
    private boolean tone;
    private Long waitSeconds;

    public CaptureScreenModel(Consumer<Screen> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** The first screen, before the device answers. */
    public synchronized Screen current() {
        return build();
    }

    /** No face device could be found or started: nothing starts, the operator is told (Part 22). */
    public synchronized void deviceUnavailable() {
        stage = Stage.STOPPED;
        message = "pramaan.error.device-unavailable";
        emit();
    }

    /** The engine could not be built: liveness is unavailable, and the capture never proceeds without it. */
    public synchronized void engineUnavailable() {
        stage = Stage.STOPPED;
        message = "pramaan.error.unavailable";
        emit();
    }

    @Override
    public synchronized void stateChanged(SessionState state) {
        Stage next = stageOf(state);
        if (next != stage) {
            prompt = null;
            stepDone = false;
        }
        if (next != Stage.RETRY) {
            waitSeconds = null;
        }
        stage = next;
        emit();
    }

    @Override
    public synchronized void guidance(Guidance g) {
        guidance = g;
        emit();
    }

    @Override
    public synchronized void message(String messageId) {
        message = messageId;
        emit();
    }

    @Override
    public synchronized void prompt(ChallengePrompt p, int step, int steps) {
        prompt = p;
        stepDone = false;
        emit();
    }

    @Override
    public synchronized void stepCompleted(int step, int steps) {
        stepDone = true;
        tone = prompt != null && prompt.audioHint().contains("tone:");
        emit();
    }

    @Override
    public synchronized void attempt(int n, int max) {
        attempt = Text.of("pramaan.status.attempt", Map.of("attempt", Integer.toString(n), "max", Integer.toString(max)));
        emit();
    }

    /** The wait before the next attempt (Build Map #93): it follows the attempt count, never a score. */
    @Override
    public synchronized void cooldown(long waitMillis) {
        waitSeconds = Math.max(1, (waitMillis + 999) / 1000);
        emit();
    }

    private void emit() {
        Screen s = build();
        tone = false;
        sink.accept(s);
    }

    private Screen build() {
        Text headline;
        Text shownGuidance = null;
        Text notice = null;
        String animation = null;
        boolean failure = message != null && !message.startsWith("pramaan.guidance.");
        switch (stage) {
            case CONNECTING:
                headline = Text.of("pramaan.status.connecting");
                break;
            case POSITIONING:
                headline = Text.of("pramaan.status.position");
                // The previous attempt's outcome stays visible while the resident repositions; the session's
                // own "look at the camera" is the guidance line's job, not a notice.
                if (failure) {
                    notice = Text.of(message);
                }
                break;
            case CHECKING:
                headline = Text.of("pramaan.status.checking");
                break;
            case CHALLENGE:
                if (prompt != null && !stepDone) {
                    headline = Text.of(prompt.messageId(), prompt.params());
                    animation = prompt.animationHint();
                } else {
                    headline = Text.of(stepDone ? "pramaan.status.action-detected" : "pramaan.status.checking");
                }
                break;
            case CAPTURING:
                headline = Text.of("pramaan.status.capturing");
                break;
            case SUCCESS:
                headline = Text.of("pramaan.status.success");
                break;
            case RETRY:
                headline = Text.of(failure ? message : "pramaan.error.try-again");
                if (waitSeconds != null) {
                    notice = Text.of("pramaan.status.wait", Map.of("seconds", Long.toString(waitSeconds)));
                }
                break;
            case STOPPED:
            default:
                // Locked out or rate limited: the operator takes over (the exception flow, Part 12).
                headline = Text.of(failure ? message : "pramaan.error.locked");
                break;
        }
        boolean guiding = stage == Stage.POSITIONING || stage == Stage.CHECKING || stage == Stage.CHALLENGE;
        if (guiding && guidance.messageId().isPresent()) {
            shownGuidance = Text.of(guidance.messageId().get());
        }
        Text shownAttempt = stage == Stage.SUCCESS || stage == Stage.STOPPED ? null : attempt;
        return new Screen(stage, headline, shownGuidance, notice, shownAttempt, animation, tone);
    }

    static Stage stageOf(SessionState state) {
        switch (state) {
            case IDLE:
            case ACQUIRING:
                return Stage.POSITIONING;
            case PASSIVE:
            case DECIDED:
                return Stage.CHECKING;
            case CHALLENGING:
                return Stage.CHALLENGE;
            case CAPTURING:
            case BINDING:
                return Stage.CAPTURING;
            case ACCEPTED:
                return Stage.SUCCESS;
            case FAILED:
            case RETRY_WAIT:
                return Stage.RETRY;
            default:
                // LOCKED_OUT, RATE_LIMITED, the exception-flow states, UNAVAILABLE: the operator takes over.
                return Stage.STOPPED;
        }
    }
}
