/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.source.DeviceLevel;

/**
 * Red-team finding R-03 (docs/red-team-log.md): cues the attacker controls (scene, moire, and a passive model assumed
 * fooled) can add up to an ACCEPT with no flash light. For operators and supervisors an ACCEPT now needs a live vote
 * from a cue the display cannot fake (flash or stereo), else the session goes to the challenge.
 */
class ZoneDeciderUnfakeableVoteTest {

    private static OperatingPointTable table;
    private static LivenessPolicy policy;
    private static final ZoneDecider NO_AUDIT = new ZoneDecider(() -> 0.999, 4.0);

    @BeforeAll
    static void load() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("..", "config", "operating-points.json"));
        Map<String, String> models = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = new ObjectMapper().readTree(bytes).get("models").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            models.put(e.getKey(), e.getValue().textValue());
        }
        table = OperatingPointTable.load(bytes, "operating-points.json", models);
        policy = YamlPolicySource.of(Path.of("..", "config", "pramaan-policy.yaml")).load();
    }

    private static CueResult v(String cue, double p) {
        return CueResult.vote(cue, p, Double.NaN);
    }

    private static FusionResult fuse(Workflow w, CueResult... cues) {
        return new RiskFusion(table).fuse(List.of(cues), w, DeviceLevel.L1);
    }

    private static Decision first(Workflow w, FusionResult f) {
        return NO_AUDIT.decide(f, table.operatingPoint(policy.profile(w).operatingPoint(), "default"), policy.profile(w),
                EnumSet.noneOf(Veto.class));
    }

    private static Decision afterChallenge(Workflow w, FusionResult f) {
        return NO_AUDIT.decideAfterChallenge(f, table.operatingPoint(policy.profile(w).operatingPoint(), "default"),
                policy.profile(w), EnumSet.noneOf(Veto.class));
    }

    /** Everything the attacker shows to the camera votes live, and the passive model is fooled too. */
    private static final CueResult[] ATTACKER_CONTROLLED = {v("passive", 0.995), v("moire", 0.995), v("scene", 0.995),
            v("colorTexture", 0.995)};

    @Test
    void theShippedPolicyTurnsTheRuleOnForOperatorsAndSupervisorsOnly() {
        assertFalse(policy.profile(Workflow.RESIDENT).requireUnfakeableVote());
        assertTrue(policy.profile(Workflow.OPERATOR).requireUnfakeableVote());
        assertTrue(policy.profile(Workflow.SUPERVISOR).requireUnfakeableVote());
    }

    @Test
    void attackerControlledCuesNoLongerAcceptAnOperatorOrASupervisor() {
        for (Workflow w : new Workflow[] {Workflow.OPERATOR, Workflow.SUPERVISOR}) {
            FusionResult f = fuse(w, ATTACKER_CONTROLLED);
            assertTrue(f.pLive() > 0.95, "the scenario must be a confident would-be accept, got " + f.pLive());
            Decision d = first(w, f);
            assertEquals(Zone.CHALLENGE, d.zone(), w + " must be sent to the challenge");
            assertTrue(d.reasons().contains(Decision.Reason.NO_DISPLAY_PROOF), w + " must say why");
        }
    }

    @Test
    void aResidentIsNotAffectedBecauseTheRuleIsOffForResidents() {
        FusionResult f = fuse(Workflow.RESIDENT, ATTACKER_CONTROLLED);
        assertEquals(Zone.ACCEPT_CANDIDATE, first(Workflow.RESIDENT, f).zone());
    }

    @Test
    void aLiveFlashVoteRestoresTheAccept() {
        FusionResult f = fuse(Workflow.OPERATOR, v("passive", 0.995), v("moire", 0.995), v("scene", 0.995),
                v("flash", 0.99));
        Decision d = first(Workflow.OPERATOR, f);
        assertEquals(Zone.ACCEPT_CANDIDATE, d.zone());
        assertFalse(d.reasons().contains(Decision.Reason.NO_DISPLAY_PROOF));
    }

    @Test
    void aStereoVoteCountsToo() {
        FusionResult f = fuse(Workflow.OPERATOR, v("passive", 0.995), v("moire", 0.995), v("scene", 0.995),
                v("stereo", 0.99));
        assertEquals(Zone.ACCEPT_CANDIDATE, first(Workflow.OPERATOR, f).zone());
        // Supervisors are audit-challenged on every first accept (probability 1.0), so check the reason, not the zone:
        // with a stereo vote the rule itself is satisfied, and the challenge that follows is the audit's.
        Decision s = first(Workflow.SUPERVISOR, fuse(Workflow.SUPERVISOR, v("passive", 0.995), v("moire", 0.995),
                v("scene", 0.995), v("stereo", 0.99)));
        assertFalse(s.reasons().contains(Decision.Reason.NO_DISPLAY_PROOF));
        assertTrue(s.reasons().contains(Decision.Reason.AUDIT));
    }

    @Test
    void aFlashCueVotingAttackIsNotALiveVote() {
        // The flash cue disagreeing is evidence against the person, never a reason to accept.
        FusionResult f = fuse(Workflow.OPERATOR, v("passive", 0.995), v("moire", 0.995), v("scene", 0.995),
                v("flash", 0.05));
        assertTrue(first(Workflow.OPERATOR, f).zone() != Zone.ACCEPT_CANDIDATE);
    }

    @Test
    void anAbstainingFlashCueIsNotALiveVoteEither() {
        FusionResult f = fuse(Workflow.OPERATOR, v("passive", 0.995), v("moire", 0.995), v("scene", 0.995),
                CueResult.abstain("flash", CueResult.AbstainReason.CAPABILITY_MISSING));
        assertEquals(Zone.CHALLENGE, first(Workflow.OPERATOR, f).zone());
    }

    @Test
    void aPassedChallengeIsTheLiveVoteSoTheReDecisionIsNotBlockedAgain() {
        FusionResult f = fuse(Workflow.OPERATOR, ATTACKER_CONTROLLED);
        assertEquals(Zone.ACCEPT_CANDIDATE, afterChallenge(Workflow.OPERATOR, f).zone(),
                "after a completed challenge the rule must not demand another one, forever");
    }

    // ---------------------------------------------------------------- residents: off by default, safe to switch on

    /** The shipped policy with the rule switched on for residents as well: a one-line change an operator could make. */
    private static LivenessPolicy residentsCovered() throws Exception {
        String yaml = new String(Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, yaml.split("onLockout: EXCEPTION_FLOW", -1).length - 1, "the RESIDENT profile's lockout line");
        return YamlPolicySource.of(yaml.replace("onLockout: EXCEPTION_FLOW", "onLockout: EXCEPTION_FLOW" + System.lineSeparator() + "      requireUnfakeableVote: true")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "residents-covered").load();
    }

    private static Decision residentFirst(LivenessPolicy p, FusionResult f, boolean accessibilityAdjusted) {
        return NO_AUDIT.decide(f, table.operatingPoint(p.profile(Workflow.RESIDENT).operatingPoint(), "default"),
                p.profile(Workflow.RESIDENT), EnumSet.noneOf(Veto.class), accessibilityAdjusted);
    }

    @Test
    void switchedOnForResidentsTheRuleSendsAttackerControlledCuesToTheChallenge() throws Exception {
        LivenessPolicy p = residentsCovered();
        assertTrue(p.profile(Workflow.RESIDENT).requireUnfakeableVote());
        Decision d = residentFirst(p, fuse(Workflow.RESIDENT, ATTACKER_CONTROLLED), false);
        assertEquals(Zone.CHALLENGE, d.zone());
        assertTrue(d.reasons().contains(Decision.Reason.NO_DISPLAY_PROOF));
    }

    @Test
    void aResidentWithAnAccessibilityProfileIsNeverForcedIntoAChallengeByTheRule() throws Exception {
        // Infant, cannot see the screen, limited movement: asked fewer or gentler checks by design. The rule stands down.
        Decision d = residentFirst(residentsCovered(), fuse(Workflow.RESIDENT, ATTACKER_CONTROLLED), true);
        assertEquals(Zone.ACCEPT_CANDIDATE, d.zone());
        assertFalse(d.reasons().contains(Decision.Reason.NO_DISPLAY_PROOF));
    }

    @Test
    void switchedOnForResidentsALiveFlashVoteStillAccepts() throws Exception {
        FusionResult f = fuse(Workflow.RESIDENT, v("passive", 0.995), v("moire", 0.995), v("scene", 0.995), v("flash", 0.99));
        assertEquals(Zone.ACCEPT_CANDIDATE, residentFirst(residentsCovered(), f, false).zone());
    }
}
