/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.audit.AuditEvent;
import pramaan.core.auth.PacketApproval;
import pramaan.core.auth.PacketApproval.Outcome;

/** Build Map #77: a supervisor approval is a fresh liveness session for this packet, used once. */
class PacketApprovalTest {

    private SessionFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SessionFixtures();
    }

    private Outcome approve(String packet, String operator, SupervisorVerdict v) {
        return PacketApproval.approve(packet, operator, v, fx.policy, fx.registry, fx.clock.instant(), fx.audit::add);
    }

    @Test
    void reusingAnOldSupervisorSessionForANewApprovalIsRejected() {
        SupervisorVerdict forP1 = fx.supervisorVerdict("SUP1", "P-1");
        assertEquals(Outcome.APPROVED, approve("P-1", "OP1", forP1));
        assertEquals(Outcome.WRONG_PACKET, approve("P-2", "OP1", forP1), "a verdict for one packet approves no other");
        assertEquals(Outcome.REUSED, approve("P-1", "OP1", forP1), "and approves its own packet once");

        SupervisorVerdict login = fx.supervisorVerdict("SUP1", "login");
        assertEquals(Outcome.WRONG_PACKET, approve("P-3", "OP1", login), "a login from earlier is not an approval");

        SupervisorVerdict old = fx.supervisorVerdict("SUP1", "P-4");
        fx.clock.advance(fx.policy.supervisorVerdictMaxAge().plus(Duration.ofSeconds(1)));
        assertEquals(Outcome.STALE, approve("P-4", "OP1", old));

        assertEquals(Outcome.SAME_PERSON, approve("P-5", "OP1", fx.supervisorVerdict("OP1", "P-5")), "dual control");
        assertEquals(Outcome.NO_VERDICT, approve("P-6", "OP1", null));
    }

    @Test
    void everyApprovalAttemptIsAuditedWithWhoAndWhat() {
        approve("P-1", "OP1", fx.supervisorVerdict("SUP1", "P-1"));
        approve("P-2", "OP1", fx.supervisorVerdict("SUP1", "P-1"));
        long approvals = fx.audit.stream().filter(e -> e.type().equals("PACKET_APPROVAL")).count();
        assertEquals(2, approvals);
        AuditEvent refused = fx.audit.stream().filter(e -> e.type().equals("PACKET_APPROVAL")
                && "WRONG_PACKET".equals(e.fields().get("outcome"))).findFirst().orElseThrow();
        assertEquals("P-2", refused.fields().get("packetId"));
        assertEquals("SUP1", refused.fields().get("supervisorId"));
        assertEquals("HIGH", refused.fields().get("severity"));
        assertTrue(fx.audit.stream().anyMatch(e -> "APPROVED".equals(e.fields().get("outcome"))));
    }

    @Test
    void aSupervisorSessionWithoutAPurposeYieldsNoVerdictAtAll() {
        LivenessSession unbound = fx.session(pramaan.core.Workflow.SUPERVISOR, "SUP1", "SUP1");
        assertTrue(unbound.approves().isEmpty());
        assertTrue(unbound.supervisorVerdict().isEmpty());
    }
}
