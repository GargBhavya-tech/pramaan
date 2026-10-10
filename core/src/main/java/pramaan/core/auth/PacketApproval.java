/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import pramaan.core.audit.AuditEvent;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SupervisorVerdict;

/**
 * Supervisor approval of one registration packet (Build Map #77): the approval is a fresh strict liveness
 * session <em>for this packet</em>. The verdict names the packet it approves from the moment the supervisor's
 * session was created ({@link pramaan.core.session.EngineBundle#newSupervisorSession}), so a login from hours
 * earlier, a verdict for another packet, or the same verdict twice approves nothing.
 *
 * <p>Checks, in order: a verdict exists; its supervisor is not the packet's operator (dual control); it
 * approves exactly this packet; it is no older than the policy allows; it has not been used before.
 */
public final class PacketApproval {

    public enum Outcome {
        APPROVED,
        NO_VERDICT,
        SAME_PERSON,
        /** The verdict approves something else: another packet, a resident exception, or nothing. */
        WRONG_PACKET,
        STALE,
        REUSED
    }

    private PacketApproval() {
    }

    public static Outcome approve(String packetId, String packetOperatorId, SupervisorVerdict verdict,
            LivenessPolicy policy, SessionRegistry registry, Instant now, Consumer<AuditEvent> audit) {
        Objects.requireNonNull(packetId, "packetId");
        Outcome outcome = judge(packetId, packetOperatorId, verdict, policy.supervisorVerdictMaxAge(), registry, now);
        AuditEvent.Builder e = AuditEvent.of("PACKET_APPROVAL").put("packetId", packetId)
                .put("operatorId", packetOperatorId).put("outcome", outcome.name())
                .put("severity", outcome == Outcome.APPROVED ? "INFO" : "HIGH");
        if (verdict != null) {
            e.put("supervisorId", verdict.supervisorId()).put("supervisorSessionId", verdict.supervisorSessionId());
        }
        audit.accept(e.build());
        return outcome;
    }

    private static Outcome judge(String packetId, String operatorId, SupervisorVerdict verdict, Duration maxAge,
            SessionRegistry registry, Instant now) {
        if (verdict == null) {
            return Outcome.NO_VERDICT;
        }
        if (verdict.supervisorId().equals(operatorId)) {
            return Outcome.SAME_PERSON;
        }
        if (!packetId.equals(verdict.approves())) {
            return Outcome.WRONG_PACKET;
        }
        if (Duration.between(verdict.acceptedAt(), now).compareTo(maxAge) > 0) {
            return Outcome.STALE;
        }
        if (!registry.spendVerdict(verdict.supervisorSessionId())) {
            return Outcome.REUSED;
        }
        return Outcome.APPROVED;
    }
}
