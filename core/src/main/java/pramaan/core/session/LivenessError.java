/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.util.Optional;

/**
 * Bible Part 22's error table, executable (Build Map #51): for every error, what the person sees (a message
 * id, never text), how serious it is for the audit log, whether it costs an attempt, and how it is handled.
 * Infrastructure errors never cost an attempt; engine and bundle errors fail closed.
 */
public enum LivenessError {
    DEVICE_UNAVAILABLE("pramaan.error.device-unavailable", Severity.WARN, false, Handling.NO_SESSION),
    DEVICE_DISCONNECTED("pramaan.error.device-unavailable", Severity.WARN, false, Handling.FAIL_ATTEMPT),
    CAMERA_BUSY("pramaan.error.camera-busy", Severity.WARN, false, Handling.FAIL_ATTEMPT),
    NO_FACE("pramaan.guidance.look-at-camera", Severity.INFO, false, Handling.GUIDANCE),
    MULTIPLE_FACES("pramaan.guidance.one-person", Severity.INFO, false, Handling.PAUSE_SCORING),
    POOR_QUALITY("pramaan.guidance.quality", Severity.INFO, false, Handling.GUIDANCE),
    PAD_FAILURE("pramaan.error.not-completed", Severity.WARN, true, Handling.FAIL_ATTEMPT),
    CHALLENGE_FAILED("pramaan.error.try-again", Severity.WARN, true, Handling.FAIL_ATTEMPT),
    CHALLENGE_TIMEOUT("pramaan.error.try-again", Severity.WARN, true, Handling.FAIL_ATTEMPT),
    MAX_RETRIES("pramaan.error.locked", Severity.WARN, false, Handling.LOCKOUT),
    /** Nothing is shown: the frame is skipped. Too many in a row become DEVICE_DISCONNECTED. */
    INVALID_FRAME(null, Severity.INFO, false, Handling.SKIP_FRAME),
    ENGINE_ERROR("pramaan.error.unavailable", Severity.HIGH, false, Handling.FAIL_CLOSED),
    INJECTION_DETECTED("pramaan.error.not-completed", Severity.HIGH, true, Handling.FAIL_ATTEMPT),
    BINDING_FAILURE("pramaan.error.not-completed", Severity.HIGH, true, Handling.FAIL_ATTEMPT),
    /** A second face still in view when the decision was made (Veto.MULTIPLE_FACES): a hard reject, not a pause. */
    VETO_MULTIPLE_FACES("pramaan.error.not-completed", Severity.HIGH, true, Handling.FAIL_ATTEMPT),
    BUNDLE_INVALID("pramaan.error.unavailable", Severity.HIGH, false, Handling.FAIL_CLOSED),
    CLOCK_ANOMALY("pramaan.error.unavailable", Severity.HIGH, false, Handling.FAIL_CLOSED);

    public enum Severity {
        INFO,
        WARN,
        /** Security-relevant: alert, not just log. */
        HIGH
    }

    public enum Handling {
        /** No session can start. */
        NO_SESSION,
        /** The attempt fails; retry rules decide what next. */
        FAIL_ATTEMPT,
        /** Guidance on screen; the state's own timeout still applies. */
        GUIDANCE,
        /** Scoring pauses until the condition clears. */
        PAUSE_SCORING,
        SKIP_FRAME,
        LOCKOUT,
        /** Liveness is unavailable. Never skipped, never accepted. */
        FAIL_CLOSED
    }

    private final String messageId;
    private final Severity severity;
    private final boolean consumesAttempt;
    private final Handling handling;

    LivenessError(String messageId, Severity severity, boolean consumesAttempt, Handling handling) {
        this.messageId = messageId;
        this.severity = severity;
        this.consumesAttempt = consumesAttempt;
        this.handling = handling;
    }

    /** The message id the UI translates; empty when nothing is shown. */
    public Optional<String> messageId() {
        return Optional.ofNullable(messageId);
    }

    public Severity severity() {
        return severity;
    }

    public boolean consumesAttempt() {
        return consumesAttempt;
    }

    public Handling handling() {
        return handling;
    }

    /**
     * The row for a REJECT_VETO, named by what was vetoed (injection first, the most serious). Every veto row
     * consumes an attempt, as REJECT_VETO does; {@link #of} alone would call every veto an injection.
     */
    public static LivenessError ofVetoes(java.util.Set<pramaan.core.fusion.Veto> vetoes) {
        if (vetoes.contains(pramaan.core.fusion.Veto.INJECTION)) {
            return INJECTION_DETECTED;
        }
        if (vetoes.contains(pramaan.core.fusion.Veto.BINDING_FAILURE)) {
            return BINDING_FAILURE;
        }
        if (vetoes.contains(pramaan.core.fusion.Veto.MULTIPLE_FACES)) {
            return VETO_MULTIPLE_FACES;
        }
        return of(FailureReason.REJECT_VETO);
    }

    /** The table row for a session failure. */
    public static LivenessError of(FailureReason reason) {
        switch (reason) {
            case NO_USABLE_FACE:
                return NO_FACE;
            case REJECT_PAD:
                return PAD_FAILURE;
            case REJECT_VETO:
                return INJECTION_DETECTED;
            case CHALLENGE_FAILED:
            case INCONCLUSIVE:
                return CHALLENGE_FAILED;
            case CHALLENGE_TIMEOUT:
                return CHALLENGE_TIMEOUT;
            case REJECT_BINDING:
                return BINDING_FAILURE;
            case DEVICE_ERROR:
            case DEVICE_TIMEOUT:
                return DEVICE_DISCONNECTED;
            case ENGINE_ERROR:
            case ENGINE_TIMEOUT:
            case NO_CHALLENGE_AVAILABLE:
                return ENGINE_ERROR;
            case CLOCK_ANOMALY:
                return CLOCK_ANOMALY;
            default:
                throw new IllegalStateException("no Part 22 row for " + reason);
        }
    }
}
