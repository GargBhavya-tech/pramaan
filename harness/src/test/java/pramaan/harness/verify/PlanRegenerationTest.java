/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.SessionCapabilities;

/**
 * Build Map #62, Bible Part 10: the server redraws the challenge sequence from the revealed seed and compares it
 * to what the evidence recorded — a client that showed an easier or pre-recordable sequence than its own seed
 * draws is caught, not merely a client whose recorded steps don't parse.
 */
class PlanRegenerationTest {

    private static SessionCapabilities caps() {
        DeviceCapabilities device = new DeviceCapabilities(new DeviceMetadata("d", "0", "code", DeviceLevel.L1, "mock",
                "face", "1"), EnumSet.noneOf(Capability.class));
        return SessionCapabilities.of(30, device);
    }

    /** The exact fields {@code EvidenceBuilder.challenges} writes, built from a real generated plan. */
    private static ObjectNode node(ChallengePlan plan, byte[] seed) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("seedCommitment", plan.seedCommitment());
        StringBuilder hex = new StringBuilder();
        for (byte b : seed) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        o.put("seed", hex.toString());
        o.put("actionBits", plan.actionBits());
        o.put("flashBits", plan.flashBits());
        o.put("lowEntropy", plan.lowEntropy());
        ArrayNode steps = o.putArray("steps");
        for (Challenge c : plan.steps()) {
            ObjectNode st = steps.addObject();
            st.put("kind", c.kind().name());
            if (c.direction() != null) {
                st.put("direction", c.direction().name());
                st.put("targetDeg", c.targetDeg());
            }
            st.put("count", c.count());
            st.put("holdMs", c.holdMs());
            st.put("gazeCell", c.gazeCell());
            st.put("onsetDelayMs", c.onsetDelayMs());
        }
        o.put("flash", plan.flash().isPresent());
        ArrayNode pool = o.putArray("pool");
        plan.policyPool().forEach(k -> pool.add(k.name()));
        o.put("minSteps", plan.minSteps());
        o.put("maxSteps", plan.maxSteps());
        o.put("fps", plan.fps());
        ArrayNode capsArr = o.putArray("capabilities");
        plan.capabilities().stream().map(Enum::name).sorted().forEach(capsArr::add);
        ArrayNode profiles = o.putArray("accessibility");
        plan.adjustments().profiles().stream().map(Enum::name).sorted().forEach(profiles::add);
        return o;
    }

    @Test
    void aCleanEvidenceRecordRegeneratesTheSamePlanAndReportsNothing() {
        List<ChallengeKind> pool = List.of(ChallengeKind.TURN, ChallengeKind.BLINK);
        ChallengeGenerator gen = ChallengeGenerator.fresh(new SecureRandom());
        ChallengePlan plan = gen.plan(pool, 3, 3, caps(), AccessibilityAdjustments.NONE);
        RecheckReport report = new RecheckReport();

        RecheckVerifier.regenerate(node(plan, gen.revealSeed()), gen.revealSeed(), report);

        assertFalse(report.has("PLAN_MISMATCH"), report.toJson().toString());
        assertFalse(report.has("PLAN_NOT_REGENERABLE"), report.toJson().toString());
        assertTrue(report.findings().isEmpty(), report.toJson().toString());
    }

    @Test
    void aStepAlteredAfterTheFactIsCaughtAsPlanMismatch() {
        List<ChallengeKind> pool = List.of(ChallengeKind.TURN, ChallengeKind.BLINK);
        ChallengeGenerator gen = ChallengeGenerator.fresh(new SecureRandom());
        ChallengePlan plan = gen.plan(pool, 2, 2, caps(), AccessibilityAdjustments.NONE);
        ObjectNode tampered = node(plan, gen.revealSeed());
        // Flip the first step's kind, as if the client's device had shown an easier action than its own seed drew.
        ArrayNode steps = (ArrayNode) tampered.get("steps");
        ObjectNode firstStep = (ObjectNode) steps.get(0);
        firstStep.put("kind", firstStep.get("kind").asText().equals("TURN") ? "BLINK" : "TURN");
        firstStep.remove("direction");
        firstStep.remove("targetDeg");

        RecheckReport report = new RecheckReport();
        RecheckVerifier.regenerate(tampered, gen.revealSeed(), report);

        assertTrue(report.has("PLAN_MISMATCH"), report.toJson().toString());
        assertTrue(report.route() == RecheckReport.Route.REJECT);
    }

    @Test
    void aStepCountAlteredAfterTheFactIsCaughtAsPlanMismatch() {
        List<ChallengeKind> pool = List.of(ChallengeKind.TURN, ChallengeKind.BLINK);
        ChallengeGenerator gen = ChallengeGenerator.fresh(new SecureRandom());
        ChallengePlan plan = gen.plan(pool, 3, 3, caps(), AccessibilityAdjustments.NONE);
        ObjectNode tampered = node(plan, gen.revealSeed());
        ((ArrayNode) tampered.get("steps")).remove(0);

        RecheckReport report = new RecheckReport();
        RecheckVerifier.regenerate(tampered, gen.revealSeed(), report);

        assertTrue(report.has("PLAN_MISMATCH"), report.toJson().toString());
    }

    @Test
    void evidenceWithNoRecordOfWhatItWasDrawnFromIsNotRegenerable() {
        ObjectNode ch = JsonNodeFactory.instance.objectNode();
        ch.put("seedCommitment", "irrelevant");
        RecheckReport report = new RecheckReport();
        RecheckVerifier.regenerate(ch, new byte[32], report);
        assertTrue(report.has("PLAN_NOT_REGENERABLE"), report.toJson().toString());
        assertFalse(report.has("PLAN_MISMATCH"));
        assertTrue(report.route() == RecheckReport.Route.ADJUDICATE, "a review finding, not a reject");
    }
}
