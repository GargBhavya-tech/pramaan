/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;

/** Build Map #9: the shipped policy loads as specified, and every kind of mistake fails loudly with a path. */
class PolicyLoaderTest {

    private static final Path SHIPPED = Path.of("..", "config", "pramaan-policy.yaml");

    private static String shippedYaml() throws IOException {
        return new String(Files.readAllBytes(SHIPPED), StandardCharsets.UTF_8);
    }

    private static LivenessPolicy load(String yaml) throws IOException {
        return YamlPolicySource.of(yaml.getBytes(StandardCharsets.UTF_8), "test.yaml").load();
    }

    private static InvalidPolicyException rejects(String yaml) {
        return assertThrows(InvalidPolicyException.class, () -> load(yaml));
    }

    @Test
    void shippedPolicyLoadsWithTheBiblesTiering() throws IOException {
        LivenessPolicy policy = YamlPolicySource.of(SHIPPED).load();

        WorkflowProfile resident = policy.profile(Workflow.RESIDENT);
        assertEquals("resident-default", resident.operatingPoint());
        assertEquals(0.15, resident.auditChallengeProbability(), 1e-9);
        assertEquals(LockoutAction.EXCEPTION_FLOW, resident.onLockout());
        assertEquals(Duration.ofSeconds(2), resident.retries().backoffBase());

        WorkflowProfile operator = policy.profile(Workflow.OPERATOR);
        assertEquals(3, operator.minCues());
        assertEquals(LockoutAction.LOCK_15_MIN_AND_ALERT, operator.onLockout());

        WorkflowProfile supervisor = policy.profile(Workflow.SUPERVISOR);
        assertEquals(1.0, supervisor.auditChallengeProbability(), 1e-9);
        assertEquals(2, supervisor.retries().maxAttempts());
        assertEquals(3, supervisor.challenge().minSteps());

        assertEquals(5, policy.accessibilityProfiles().size());
        assertTrue(policy.exceptionRequiresSupervisor());
        assertEquals(112, policy.evidenceCropSize());
    }

    @Test
    void aTypoIsAnErrorWithItsFullPathNotASilentDefault() throws IOException {
        InvalidPolicyException e = rejects(shippedYaml().replaceFirst("stepTimeoutMs", "stepTimoutMs"));
        assertTrue(e.getMessage().contains("pramaan.profiles.RESIDENT.challenge.stepTimeoutMs")
                        || e.getMessage().contains("pramaan.profiles.RESIDENT.challenge.stepTimoutMs"),
                e.getMessage());
    }

    @Test
    void anExtraUnknownFieldIsRejected() throws IOException {
        InvalidPolicyException e = rejects(shippedYaml().replaceFirst("  diagnosticMode: false", "  diagnosticMode: false\n  threshold: 0.5"));
        assertTrue(e.getMessage().contains("pramaan.threshold"), e.getMessage());
    }

    @Test
    void numbersWrittenAsTextAreRejected() throws IOException {
        InvalidPolicyException e = rejects(shippedYaml().replaceFirst("minCues: 2", "minCues: \"2\""));
        assertTrue(e.getMessage().contains("pramaan.profiles.RESIDENT.minCues"), e.getMessage());
    }

    @Test
    void outOfRangeValuesAreRejected() throws IOException {
        assertTrue(rejects(shippedYaml().replaceFirst("auditChallengeProbability: 0.15", "auditChallengeProbability: 1.5"))
                .getMessage().contains("auditChallengeProbability"));
        assertTrue(rejects(shippedYaml().replaceFirst("minSteps: 1", "minSteps: 4"))
                .getMessage().contains("minSteps"));
        assertTrue(rejects(shippedYaml().replaceFirst("perSubjectPerHour: 10", "perSubjectPerHour: 0"))
                .getMessage().contains("perSubjectPerHour"));
        assertTrue(rejects(shippedYaml().replaceFirst("acquireTimeoutMs: 20000", "acquireTimeoutMs: 50"))
                .getMessage().contains("timeouts too short"));
    }

    @Test
    void theExceptionPathCannotBeMadeSingleControl() throws IOException {
        // Dual control is the point of the exception flow (Bible Part 12); a policy cannot switch it off.
        assertTrue(rejects(shippedYaml().replaceFirst("requireSupervisor: true", "requireSupervisor: false"))
                .getMessage().contains("requireSupervisor"));
    }

    @Test
    void aMissingWorkflowProfileIsRejected() throws IOException {
        String yaml = shippedYaml();
        String withoutSupervisor = yaml.substring(0, yaml.indexOf("    SUPERVISOR:")) + yaml.substring(yaml.indexOf("  accessibilityProfiles"));
        assertTrue(rejects(withoutSupervisor).getMessage().contains("pramaan.profiles.SUPERVISOR"));
    }

    @Test
    void unknownEnumValuesListTheAllowedOnes() throws IOException {
        InvalidPolicyException e = rejects(shippedYaml().replaceFirst("pool: \\[TURN, EYES_CLOSED, BLINK, SMILE", "pool: [TURN, DANCE, BLINK, SMILE"));
        assertTrue(e.getMessage().contains("DANCE") && e.getMessage().contains("EYES_CLOSED"), e.getMessage());
    }

    @Test
    void duplicateKeysAreRejectedRatherThanLastOneWins() throws IOException {
        rejects(shippedYaml().replaceFirst("  enabled: true", "  enabled: true\n  enabled: false"));
    }

    @Test
    void supervisorsCanNeverBeLessStrictThanOperators() throws IOException {
        InvalidPolicyException e = rejects(shippedYaml().replaceFirst("auditChallengeProbability: 1.0", "auditChallengeProbability: 0.1"));
        assertTrue(e.getMessage().contains("must not decrease"), e.getMessage());
    }
}
