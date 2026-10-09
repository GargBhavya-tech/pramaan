/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.time.Instant;

/**
 * Proof that a supervisor passed their own strict liveness session (Build Map #50). There is no public
 * constructor: the only way to get one is {@link LivenessSession#supervisorVerdict()} on a SUPERVISOR
 * session that reached ACCEPTED. An exception cannot be approved with a flag, a name or a password.
 */
public final class SupervisorVerdict {

    private final String supervisorSessionId;
    private final String supervisorId;
    private final Instant acceptedAt;
    private final String approves;

    SupervisorVerdict(String supervisorSessionId, String supervisorId, Instant acceptedAt, String approves) {
        this.supervisorSessionId = supervisorSessionId;
        this.supervisorId = supervisorId;
        this.acceptedAt = acceptedAt;
        this.approves = approves;
    }

    public String supervisorSessionId() {
        return supervisorSessionId;
    }

    public String supervisorId() {
        return supervisorId;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    /**
     * The one thing this verdict approves — a packet id, or the resident session of an exception — fixed when
     * the supervisor session was created, before the face was checked (Build Map #77).
     */
    public String approves() {
        return approves;
    }
}
