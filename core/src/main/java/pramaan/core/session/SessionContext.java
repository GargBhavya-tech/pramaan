/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import pramaan.core.Workflow;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.WorkflowProfile;

/** Everything a session is born with (Build Map #18). Immutable. */
public final class SessionContext {

    private final String sessionId;
    private final SessionNonce nonce;
    private final Workflow workflow;
    private final LivenessPolicy policy;
    private final Clock clock;
    private final Instant startedAt;

    private SessionContext(String sessionId, SessionNonce nonce, Workflow workflow, LivenessPolicy policy, Clock clock) {
        this.sessionId = sessionId;
        this.nonce = nonce;
        this.workflow = workflow;
        this.policy = policy;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** Starts a session with a fresh nonce; the session id is a second, independent random value. */
    public static SessionContext start(Workflow workflow, LivenessPolicy policy, Clock clock, SecureRandom random) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(clock, "clock");
        SessionNonce nonce = SessionNonce.generate(random);
        String sessionId = "s-" + SessionNonce.generate(random).value().substring(0, 12);
        return new SessionContext(sessionId, nonce, workflow, policy, clock);
    }

    public String sessionId() {
        return sessionId;
    }

    public SessionNonce nonce() {
        return nonce;
    }

    public Workflow workflow() {
        return workflow;
    }

    public LivenessPolicy policy() {
        return policy;
    }

    public WorkflowProfile profile() {
        return policy.profile(workflow);
    }

    public Clock clock() {
        return clock;
    }

    public Instant startedAt() {
        return startedAt;
    }
}
