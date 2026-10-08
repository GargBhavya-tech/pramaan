/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.InvalidPolicyException;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPoint;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.WorkflowProfile;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.source.DeviceLevel;

/** Build Map #35-#40 against the checked-in provisional table and policy. */
class RiskFusionTest {

    private static byte[] tableBytes;
    private static Map<String, String> tableModels;
    private static OperatingPointTable table;
    private static LivenessPolicy policy;

    @BeforeAll
    static void load() throws Exception {
        tableBytes = Files.readAllBytes(Path.of("..", "config", "operating-points.json"));
        // Pretend the loaded models are exactly the ones the table names; the mismatch test changes one.
        tableModels = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = new ObjectMapper().readTree(tableBytes).get("models").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            tableModels.put(e.getKey(), e.getValue().textValue());
        }
        table = OperatingPointTable.load(tableBytes, "operating-points.json", tableModels);
        policy = YamlPolicySource.of(Path.of("..", "config", "pramaan-policy.yaml")).load();
    }

    private static CueResult v(String cue, double p) {
        return CueResult.vote(cue, p, Double.NaN);
    }

    private static FusionResult fuse(Workflow w, DeviceLevel level, CueResult... cues) {
        return new RiskFusion(table).fuse(List.of(cues), w, level);
    }

    private static OperatingPoint point(Workflow w) {
        return table.operatingPoint(policy.profile(w).operatingPoint(), "default");
    }

    /** Never audits (always draws 0.999). */
    private static final ZoneDecider NO_AUDIT = new ZoneDecider(() -> 0.999, 4.0);

    private static Decision decide(Workflow w, FusionResult f) {
        return NO_AUDIT.decide(f, point(w), policy.profile(w), EnumSet.noneOf(Veto.class));
    }

    // ---------------------------------------------------------------- #35
    @Test
    void theBibleWorkedExampleIsReproduced() {
        // Part 9: passive 0.62, colour-texture 0.40, moire 0.70; flatness and flash abstain; L1, b = 0.
        FusionResult before = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.62), v("colorTexture", 0.40),
                v("moire", 0.70), CueResult.abstain("flatness", CueResult.AbstainReason.NOT_ENOUGH_MOTION),
                CueResult.abstain("flash", CueResult.AbstainReason.CAPABILITY_MISSING));
        assertEquals(0.84, before.logOdds(), 0.01);
        assertEquals(0.70, before.pLive(), 0.01);
        Decision d = decide(Workflow.RESIDENT, before);
        assertEquals(Zone.CHALLENGE, d.zone());
        assertEquals(Tier.BLUE, d.tier());

        // The head turn unlocks flatness 0.93: L = 0.84 + 1.2 * 2.59 = 3.95, P = 0.98, accept.
        FusionResult after = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.62), v("colorTexture", 0.40),
                v("moire", 0.70), v("flatness", 0.93));
        assertEquals(3.95, after.logOdds(), 0.01);
        assertEquals(Zone.ACCEPT_CANDIDATE, decide(Workflow.RESIDENT, after).zone());
    }

    @Test
    void anAbstainingCueChangesNothing() {
        FusionResult a = fuse(Workflow.OPERATOR, DeviceLevel.L1, v("passive", 0.8), v("moire", 0.6));
        FusionResult b = fuse(Workflow.OPERATOR, DeviceLevel.L1, v("passive", 0.8), v("moire", 0.6),
                CueResult.abstain("rppg", CueResult.AbstainReason.FRAME_RATE_TOO_LOW),
                CueResult.abstain("colorTexture", CueResult.AbstainReason.UNTRAINED));
        assertEquals(a.logOdds(), b.logOdds(), 0.0);
        assertEquals(a.contributing(), b.contributing());
        assertEquals(a.logitGap(), b.logitGap(), 0.0);
    }

    @Test
    void aMissingReflexCanNeverCountAgainstAResident() {
        FusionResult without = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.97), v("moire", 0.9));
        for (String physiological : new String[] {"rppg", "deformation"}) {
            for (int i = 1; i < 1000; i++) {
                double p = i / 1000.0;
                FusionResult with = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.97), v("moire", 0.9), v(physiological, p));
                assertTrue(with.logOdds() >= without.logOdds(), physiological + " at p=" + p + " lowered a resident's score");
                assertEquals(without.logitGap(), with.logitGap(), 0.0,
                        "a reflex, present or absent, must not trigger the disagreement rule for a resident here");
                assertEquals(decide(Workflow.RESIDENT, without).zone(), decide(Workflow.RESIDENT, with).zone(),
                        physiological + " at p=" + p + " changed a resident's zone");
            }
        }
    }

    @Test
    void theSameReflexCountsAgainstAnOperator() {
        FusionResult resident = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.9), v("moire", 0.8), v("rppg", 0.05));
        FusionResult operator = fuse(Workflow.OPERATOR, DeviceLevel.L1, v("passive", 0.9), v("moire", 0.8), v("rppg", 0.05));
        assertEquals(2, resident.contributing());
        assertEquals(3, operator.contributing());
        assertEquals(resident.logOdds() + 0.3 * Math.log(0.05 / 0.95), operator.logOdds(), 1e-9);
    }

    @Test
    void aVotingCueWithoutAWeightFailsClosed() {
        assertThrows(IllegalStateException.class, () -> fuse(Workflow.RESIDENT, DeviceLevel.L1, v("mystery", 0.9)));
        assertThrows(IllegalStateException.class,
                () -> fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.9), v("passive", 0.8)));
    }

    // ---------------------------------------------------------------- #36
    @Test
    void aClearAttackIsRejectedWithoutBeingOfferedAChallenge() {
        FusionResult f = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.6), v("moire", 0.02));
        Decision d = decide(Workflow.RESIDENT, f);
        assertEquals(Zone.REJECT_PAD, d.zone());
        assertEquals(Tier.RED, d.tier());
        assertTrue(f.logitGap() > 4, "the cues disagree, yet a reject must stay a reject: gap " + f.logitGap());
    }

    @Test
    void aHardVetoRejectsWhateverTheScore() {
        FusionResult glowing = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.99), v("moire", 0.99));
        Decision d = NO_AUDIT.decide(glowing, point(Workflow.RESIDENT), policy.profile(Workflow.RESIDENT),
                EnumSet.of(Veto.INJECTION));
        assertEquals(Zone.REJECT_PAD, d.zone());
        assertEquals(Set.of(Decision.Reason.VETO), d.reasons());
        assertNull(d.fusion());
    }

    // ---------------------------------------------------------------- #38
    @Test
    void confidentPassiveAgainstStrongMoireForcesAChallengeInsteadOfAccepting() {
        FusionResult f = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.95), v("moire", 0.05), v("flatness", 0.99));
        assertTrue(f.pLive() >= point(Workflow.RESIDENT).tauAccept(), "the average alone would accept: " + f.pLive());
        Decision d = decide(Workflow.RESIDENT, f);
        assertEquals(Zone.CHALLENGE, d.zone());
        assertTrue(d.reasons().contains(Decision.Reason.DISAGREEMENT));
    }

    @Test
    void tooFewCuesTurnsBothAcceptAndRejectIntoAChallenge() {
        Decision accept = decide(Workflow.RESIDENT, fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.99)));
        Decision reject = decide(Workflow.RESIDENT, fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.01)));
        assertEquals(Zone.CHALLENGE, accept.zone());
        assertEquals(Zone.CHALLENGE, reject.zone());
        assertTrue(accept.reasons().contains(Decision.Reason.TOO_FEW_CUES));
        assertTrue(reject.reasons().contains(Decision.Reason.TOO_FEW_CUES));
        // Operators need three cues: two confident ones are still not enough.
        Decision op = decide(Workflow.OPERATOR, fuse(Workflow.OPERATOR, DeviceLevel.L1, v("passive", 0.99), v("moire", 0.99)));
        assertEquals(Zone.CHALLENGE, op.zone());
    }

    // ---------------------------------------------------------------- #39
    @Test
    void auditRateMatchesPolicyAndDelaysDoNotRevealThePath() {
        Random random = new Random(20260923);
        ZoneDecider decider = new ZoneDecider(random::nextDouble, table.disagreementLogitGap());
        FusionResult white = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.97), v("moire", 0.9));
        FusionResult red = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.1), v("moire", 0.05));
        WorkflowProfile resident = policy.profile(Workflow.RESIDENT);
        int audits = 0;
        List<Long> acceptDelays = new ArrayList<>();
        List<Long> rejectDelays = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            Decision d = decider.decide(white, point(Workflow.RESIDENT), resident, EnumSet.noneOf(Veto.class));
            assertEquals(Tier.WHITE, d.tier());
            if (d.reasons().contains(Decision.Reason.AUDIT)) {
                audits++;
                assertEquals(Zone.CHALLENGE, d.zone(), "an audit must look exactly like any other challenge");
            } else {
                acceptDelays.add(d.displayDelayMillis());
            }
            Decision r = decider.decide(red, point(Workflow.RESIDENT), resident, EnumSet.noneOf(Veto.class));
            assertEquals(Zone.REJECT_PAD, r.zone());
            rejectDelays.add(r.displayDelayMillis());
        }
        assertEquals(resident.auditChallengeProbability(), audits / 10_000.0, 0.01);
        for (List<Long> delays : List.of(acceptDelays, rejectDelays)) {
            assertEquals(200L, delays.stream().mapToLong(Long::longValue).min().orElseThrow(), 3);
            assertEquals(600L, delays.stream().mapToLong(Long::longValue).max().orElseThrow(), 3);
        }
        double meanAccept = acceptDelays.stream().mapToLong(Long::longValue).average().orElseThrow();
        double meanReject = rejectDelays.stream().mapToLong(Long::longValue).average().orElseThrow();
        assertEquals(meanAccept, meanReject, 10, "accept and reject timing must be indistinguishable");
    }

    // ---------------------------------------------------------------- #40
    @Test
    void anL0DeviceNeedsMoreEvidenceAtTheBoundary() {
        // L sits just above logit(0.85) = 1.73 on L1, and L0's -0.5 bias takes it below.
        FusionResult l1 = fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.80), v("moire", 0.72));
        FusionResult l0 = fuse(Workflow.RESIDENT, DeviceLevel.L0, v("passive", 0.80), v("moire", 0.72));
        assertEquals(Zone.ACCEPT_CANDIDATE, decide(Workflow.RESIDENT, l1).zone(), "L1 P=" + l1.pLive());
        assertEquals(Zone.CHALLENGE, decide(Workflow.RESIDENT, l0).zone(), "L0 P=" + l0.pLive());
    }

    // ---------------------------------------------------------------- #37
    @Test
    void swappingAModelWithoutRegeneratingTheTableFailsClosed() {
        Map<String, String> swapped = new TreeMap<>(tableModels);
        swapped.put("minifasnet_v2_2.7_80x80.onnx", OperatingPointTable.sha256Hex(new byte[] {1, 2, 3}));
        InvalidPolicyException e = assertThrows(InvalidPolicyException.class,
                () -> OperatingPointTable.load(tableBytes, "operating-points.json", swapped));
        assertTrue(e.getMessage().contains("minifasnet_v2_2.7_80x80.onnx") && e.getMessage().contains("Regenerate"),
                e.getMessage());

        Map<String, String> extra = new TreeMap<>(tableModels);
        extra.put("new_detector.onnx", OperatingPointTable.sha256Hex(new byte[0]));
        assertThrows(InvalidPolicyException.class, () -> OperatingPointTable.load(tableBytes, "t", extra));
        Map<String, String> missing = new TreeMap<>(tableModels);
        missing.remove("face_detection_yunet_2023mar.onnx");
        assertThrows(InvalidPolicyException.class, () -> OperatingPointTable.load(tableBytes, "t", missing));
    }

    @Test
    void theTableIsParsedStrictlyAndCoversEveryPolicyProfile() {
        table.requireCovers(policy);
        assertTrue(table.provisional(), "the checked-in table is hand-written and must say so");
        String json = new String(tableBytes, StandardCharsets.UTF_8);
        String[] broken = {
            json.replace("\"tauReject\": 0.20", "\"tauReject\": 0.90"),
            json.replace("\"disagreementLogitGap\"", "\"disagreementLogitGapp\""),
            json.replace("\"L0\": -0.5", "\"L0\": 0.5"),
            json.replace("\"wNeg\": 0.3", "\"wNeg\": -0.3"),
            json.replace("\"tableVersion\": 1,", "\"tableVersion\": 1, \"tableVersion\": 1,"),
            json.replace("\"tauAccept\": 0.85", "\"tauAccept\": \"0.85\""),
            json.replace("\"default\"", "\"desktop\"")};
        for (String b : broken) {
            assertNotEquals(json, b, "the mutation must apply");
            assertThrows(InvalidPolicyException.class,
                    () -> OperatingPointTable.load(b.getBytes(StandardCharsets.UTF_8), "t", tableModels));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                OperatingPointTable.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void decisionsBecomeAuditEvents() {
        Decision d = decide(Workflow.RESIDENT, fuse(Workflow.RESIDENT, DeviceLevel.L1, v("passive", 0.6), v("moire", 0.02)));
        AuditEvent e = d.toAuditEvent("s-01");
        assertEquals("REJECT_PAD", e.fields().get("zone"));
        assertEquals("resident-default", e.fields().get("operatingPoint"));
        assertEquals(2L, e.fields().get("contributing"));
    }
}
