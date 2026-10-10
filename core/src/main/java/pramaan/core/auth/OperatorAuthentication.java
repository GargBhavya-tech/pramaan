/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionState;
import pramaan.core.source.SignedCapture;

/**
 * Operator login by face (Build Map #76; Bible Part 15): strict liveness <em>first</em>, then the 1:1 match.
 *
 * <p>The order is the point. A match run on a capture that failed liveness would tell whoever held up the
 * photo that it is the right face; so the matcher is not even called unless the operator's own OPERATOR-profile
 * session reached ACCEPTED, which it can only do through capture binding — the face matched is the face that
 * passed liveness. Every outcome is audited, and nothing but a match on a bound, live capture authenticates:
 * a matcher error is a refusal, never a pass.
 */
public final class OperatorAuthentication {

    public enum Outcome {
        AUTHENTICATED,
        /** Liveness or binding did not pass; the matcher was never called. */
        LIVENESS_NOT_PASSED,
        FACE_NOT_MATCHED,
        /** The matcher failed; not authenticated (fail closed). */
        MATCH_UNAVAILABLE
    }

    private OperatorAuthentication() {
    }

    /**
     * @param engine the engine that ran this operator's liveness session, and holds its accepted capture
     */
    public static Outcome authenticate(String operatorId, LivenessEngine engine, FaceMatcher matcher,
            Consumer<AuditEvent> audit) {
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(matcher, "matcher");
        LivenessSession s = engine.session();
        if (s.workflow() != Workflow.OPERATOR) {
            throw new IllegalArgumentException("operator login needs the OPERATOR profile, not " + s.workflow());
        }
        if (!s.subjectId().equals(operatorId)) {
            throw new IllegalArgumentException("that liveness session checked someone other than " + operatorId);
        }
        Optional<SignedCapture> capture = engine.acceptedCapture();
        Outcome outcome;
        if (s.state() != SessionState.ACCEPTED || capture.isEmpty() || s.bindingFinalHash() == null) {
            outcome = Outcome.LIVENESS_NOT_PASSED;
        } else {
            try {
                outcome = matcher.matches(capture.get(), operatorId) ? Outcome.AUTHENTICATED : Outcome.FACE_NOT_MATCHED;
            } catch (Exception e) {
                outcome = Outcome.MATCH_UNAVAILABLE;
            }
        }
        audit.accept(AuditEvent.of("OPERATOR_AUTH").put("operatorId", operatorId).put("sessionId", s.sessionId())
                .put("outcome", outcome.name()).put("severity", outcome == Outcome.AUTHENTICATED ? "INFO" : "HIGH").build());
        return outcome;
    }
}
