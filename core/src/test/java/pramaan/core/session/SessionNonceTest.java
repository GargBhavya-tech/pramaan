/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.source.CaptureRequest;
import pramaan.core.testing.TestClock;

/** Build Map #18: nonces are unique, hex, SBI-valid, and never derived from the clock. */
class SessionNonceTest {

    @Test
    void tenThousandNoncesAreUniqueHexAndValidTransactionIds() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String nonce = SessionNonce.generate(random).value();
            assertTrue(SessionNonce.FORMAT.matcher(nonce).matches(), nonce);
            assertTrue(CaptureRequest.TRANSACTION_ID.matcher(nonce).matches(), nonce);
            assertTrue(seen.add(nonce), "duplicate nonce " + nonce);
        }
        assertEquals(10_000, seen.size());
    }

    @Test
    void sessionsStartedAtTheSameInstantStillGetDifferentNonces() throws Exception {
        LivenessPolicy policy = YamlPolicySource.of(Path.of("..", "config", "pramaan-policy.yaml")).load();
        TestClock frozen = TestClock.at("2026-09-23T10:00:00Z");
        SecureRandom random = new SecureRandom();
        SessionContext a = SessionContext.start(Workflow.RESIDENT, policy, frozen, random);
        SessionContext b = SessionContext.start(Workflow.RESIDENT, policy, frozen, random);
        assertNotEquals(a.nonce(), b.nonce());
        assertNotEquals(a.sessionId(), b.sessionId());
        assertEquals(frozen.instant(), a.startedAt());
        assertEquals("resident-default", a.profile().operatingPoint());
    }
}
