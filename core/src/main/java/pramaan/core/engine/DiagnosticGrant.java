/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import pramaan.core.audit.AuditEvent;
import pramaan.core.session.EngineBundle;

/**
 * Permission to attach a {@link DiagnosticSink} (Build Map #78; Bible Part 19.3). It exists only if the running
 * policy says {@code diagnosticMode: true} — off by default — and creating it is itself an audited event naming
 * the administrator and the reason; every session it is attached to is audited again. It is bound to the
 * policy it was granted under: a grant from one bundle does not open another.
 */
public final class DiagnosticGrant {

    private final String adminId;
    private final String policySha256;
    private final Consumer<AuditEvent> audit;

    private DiagnosticGrant(String adminId, String policySha256, Consumer<AuditEvent> audit) {
        this.adminId = adminId;
        this.policySha256 = policySha256;
        this.audit = audit;
    }

    /**
     * @throws IllegalStateException when the policy does not allow diagnostic mode (the refusal is audited too)
     */
    public static DiagnosticGrant activate(EngineBundle bundle, String adminId, String reason, Instant at,
            Consumer<AuditEvent> audit) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(reason, "reason");
        boolean allowed = bundle.policy().diagnosticMode();
        audit.accept(AuditEvent.of(allowed ? "DIAGNOSTIC_MODE_ACTIVATED" : "DIAGNOSTIC_MODE_REFUSED")
                .put("adminId", adminId).put("reason", reason).put("at", at.toString())
                .put("policySha256", bundle.policySha256()).put("severity", "HIGH").build());
        if (!allowed) {
            throw new IllegalStateException("diagnostic mode is disabled by policy");
        }
        return new DiagnosticGrant(adminId, bundle.policySha256(), audit);
    }

    public String adminId() {
        return adminId;
    }

    String policySha256() {
        return policySha256;
    }

    void attached(String sessionId) {
        audit.accept(AuditEvent.of("DIAGNOSTICS_ATTACHED").put("adminId", adminId).put("sessionId", sessionId)
                .put("severity", "HIGH").build());
    }
}
